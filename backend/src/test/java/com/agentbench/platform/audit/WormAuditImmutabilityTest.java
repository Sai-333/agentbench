package com.agentbench.platform.audit;

import com.agentbench.platform.rls.RlsContext;
import com.agentbench.support.PostgresTestSupport;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Proves WORM audit storage: {@code agentbench_app} cannot UPDATE/DELETE audit rows,
 * dual-write from evaluation transitions populates the hash chain, and
 * {@link WormAuditService#verifyChain} detects superuser tampering after triggers are disabled.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class WormAuditImmutabilityTest {

    private static final String REFERENCE_PATCH = """
            diff --git a/src/Bug.java b/src/Bug.java
            --- a/src/Bug.java
            +++ b/src/Bug.java
            @@ -1 +1 @@
            -class Bug { String value; String get() { return value; } }
            +class Bug { String value; String get() { return java.util.Objects.requireNonNull(value); } }
            """;

    private static final String FIXTURE = """
            {
              "workspace": {
                "src/Bug.java": "class Bug { String value; String get() { return value; } }\\n"
              },
              "allowedPaths": ["src/"],
              "cases": [
                {
                  "key": "target.nullSafe",
                  "role": "target",
                  "baselineChecks": [
                    {"type": "file_contains", "path": "src/Bug.java", "needle": "Objects.requireNonNull", "expectPresent": true}
                  ],
                  "postChecks": [
                    {"type": "file_contains", "path": "src/Bug.java", "needle": "Objects.requireNonNull", "expectPresent": true}
                  ]
                }
              ]
            }
            """;

    @Container
    static PostgreSQLContainer<?> postgres = PostgresTestSupport.postgres();

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        PostgresTestSupport.registerRlsAwareDatasource(registry, postgres);
        registry.add("agentbench.evaluation.listener-enabled", () -> "false");
    }

    @Autowired
    MockMvc mockMvc;

    @Autowired
    NamedParameterJdbcTemplate jdbc;

    @Autowired
    WormAuditService wormAuditService;

    @Autowired
    ObjectMapper objectMapper;

    @Test
    void appRoleCannotUpdateOrDeleteWormOrTransitionRows() throws Exception {
        String token = register("worm-" + UUID.randomUUID() + "@example.com", "WormUser");
        UUID orgId = createOrg(token, "Worm Org " + UUID.randomUUID(), "worm-" + UUID.randomUUID().toString().substring(0, 8));

        RlsContext.runWithOrg(orgId, () -> {
            WormAuditEntry entry = wormAuditService.append(
                    orgId,
                    "test.manual",
                    "tester",
                    "fixture",
                    "row-1",
                    "{\"ok\":true}");

            assertWormMutationDenied(() ->
                    jdbc.update(
                            "UPDATE platform.worm_audit_events SET actor = 'pwned' WHERE id = :id",
                            new MapSqlParameterSource("id", entry.id())));
            assertWormMutationDenied(() ->
                    jdbc.update(
                            "DELETE FROM platform.worm_audit_events WHERE id = :id",
                            new MapSqlParameterSource("id", entry.id())));

            WormAuditService.ChainVerification verification = wormAuditService.verifyChain(orgId);
            assertTrue(verification.intact(), verification.detail());
        });
    }

    @Test
    void evaluationTransitionDualWritesAndTransitionUpdateDeleteBlocked() throws Exception {
        String token = register("worm-tr-" + UUID.randomUUID() + "@example.com", "WormTr");
        UUID orgId = createOrg(token, "Worm Tr Org " + UUID.randomUUID(), "wtr-" + UUID.randomUUID().toString().substring(0, 8));
        UUID evaluationId = createQueuedEvaluation(token, orgId);

        RlsContext.runWithOrg(orgId, () -> {
            Integer dualWrites = jdbc.queryForObject("""
                    SELECT COUNT(*) FROM platform.worm_audit_events
                    WHERE org_id = :orgId
                      AND event_type = 'evaluation.state_transition'
                      AND resource_id = :evaluationId
                    """,
                    new MapSqlParameterSource()
                            .addValue("orgId", orgId)
                            .addValue("evaluationId", evaluationId.toString()),
                    Integer.class);
            assertTrue(dualWrites != null && dualWrites >= 1, "dual-write must copy state transitions");

            Long transitionId = jdbc.queryForObject("""
                    SELECT id FROM evaluations.evaluation_state_transitions
                    WHERE org_id = :orgId AND evaluation_id = :evaluationId
                    ORDER BY id ASC LIMIT 1
                    """,
                    new MapSqlParameterSource()
                            .addValue("orgId", orgId)
                            .addValue("evaluationId", evaluationId),
                    Long.class);

            assertWormMutationDenied(() ->
                    jdbc.update(
                            "UPDATE evaluations.evaluation_state_transitions SET reason = 'tamper' WHERE id = :id",
                            new MapSqlParameterSource("id", transitionId)));
            assertWormMutationDenied(() ->
                    jdbc.update(
                            "DELETE FROM evaluations.evaluation_state_transitions WHERE id = :id",
                            new MapSqlParameterSource("id", transitionId)));

            assertTrue(wormAuditService.verifyChain(orgId).intact());
        });
    }

    @Test
    void hashChainDetectsSuperuserTamperAfterTriggerDisable() throws Exception {
        String token = register("worm-hash-" + UUID.randomUUID() + "@example.com", "WormHash");
        UUID orgId = createOrg(token, "Worm Hash Org " + UUID.randomUUID(), "wh-" + UUID.randomUUID().toString().substring(0, 8));

        long entryId = RlsContext.callWithOrg(orgId, () ->
                wormAuditService.append(orgId, "test.chain", "tester", "fixture", "a", "{\"n\":1}").id());

        try (Connection conn = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             Statement st = conn.createStatement()) {
            st.execute("ALTER TABLE platform.worm_audit_events DISABLE TRIGGER worm_audit_events_worm_deny");
            st.execute("UPDATE platform.worm_audit_events SET actor = 'evil' WHERE id = " + entryId);
            st.execute("ALTER TABLE platform.worm_audit_events ENABLE TRIGGER worm_audit_events_worm_deny");
        }

        RlsContext.runWithOrg(orgId, () -> {
            WormAuditService.ChainVerification verification = wormAuditService.verifyChain(orgId);
            assertFalse(verification.intact(), "tampered actor must break entry_hash");
            assertEquals(entryId, verification.brokenId());
        });
    }

    private static void assertWormMutationDenied(Runnable mutation) {
        DataAccessException denied = assertThrows(DataAccessException.class, mutation::run);
        String message = (denied.getMostSpecificCause() == null
                ? denied.getMessage()
                : denied.getMostSpecificCause().getMessage()).toLowerCase();
        assertTrue(
                message.contains("append-only") || message.contains("permission") || message.contains("privilege"),
                () -> "Expected WORM deny or privilege revoke, got: " + denied.getMessage());
    }

    private UUID createQueuedEvaluation(String token, UUID orgId) throws Exception {
        MvcResult bench = mockMvc.perform(post("/api/v1/orgs/{orgId}/benchmarks", orgId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Worm Bench %s","description":"worm"}
                                """.formatted(UUID.randomUUID().toString().substring(0, 8))))
                .andExpect(status().isCreated())
                .andReturn();
        UUID benchmarkId = UUID.fromString(objectMapper.readTree(bench.getResponse().getContentAsString()).get("id").asText());

        MvcResult version = mockMvc.perform(post("/api/v1/orgs/{orgId}/benchmarks/{benchmarkId}/versions", orgId, benchmarkId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"version":"1.0.0","changelog":"worm"}
                                """))
                .andExpect(status().isCreated())
                .andReturn();
        UUID versionId = UUID.fromString(objectMapper.readTree(version.getResponse().getContentAsString()).get("id").asText());

        String taskBody = """
                {
                  "slug":"worm-task",
                  "title":"Worm task",
                  "prompt":"Make get() null-safe",
                  "acceptanceCriteria":"requireNonNull",
                  "referencePatch":%s,
                  "timeoutSec":600,
                  "memoryMb":512,
                  "maxPatchBytes":100000,
                  "evalConfig":{"requireTargetFailAtBaseline":true,"allowedPatchPaths":["src/"]}
                }
                """.formatted(objectMapper.writeValueAsString(REFERENCE_PATCH));
        MvcResult task = mockMvc.perform(post("/api/v1/orgs/{orgId}/benchmark-versions/{versionId}/tasks", orgId, versionId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(taskBody))
                .andExpect(status().isCreated())
                .andReturn();
        UUID taskId = UUID.fromString(objectMapper.readTree(task.getResponse().getContentAsString()).get("id").asText());

        String suiteBody = objectMapper.createObjectNode()
                .put("framework", "fixture-v1")
                .put("entryCommand", "fixture")
                .put("fixtureContent", FIXTURE)
                .put("caseCount", 1)
                .set("expectedOutcomes", objectMapper.createObjectNode())
                .toString();
        mockMvc.perform(put("/api/v1/orgs/{orgId}/tasks/{taskId}/suites/visible", orgId, taskId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(suiteBody))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/orgs/{orgId}/benchmark-versions/{versionId}/publish", orgId, versionId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        String patchJson = objectMapper.writeValueAsString(REFERENCE_PATCH);
        MvcResult agent = mockMvc.perform(post("/api/v1/orgs/{orgId}/agents", orgId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Fake-%s","adapterType":"FAKE","config":{"patch":%s}}
                                """.formatted(UUID.randomUUID().toString().substring(0, 8), patchJson)))
                .andExpect(status().isCreated())
                .andReturn();
        UUID agentId = UUID.fromString(objectMapper.readTree(agent.getResponse().getContentAsString()).get("id").asText());

        MvcResult eval = mockMvc.perform(post("/api/v1/orgs/{orgId}/evaluations", orgId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"benchmarkVersionId":"%s","taskId":"%s","agentId":"%s"}
                                """.formatted(versionId, taskId, agentId)))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(objectMapper.readTree(eval.getResponse().getContentAsString()).get("id").asText());
    }

    private String register(String email, String displayName) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"password123","displayName":"%s"}
                                """.formatted(email, displayName)))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("accessToken").asText();
    }

    private UUID createOrg(String token, String name, String slug) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/orgs")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"%s","slug":"%s"}
                                """.formatted(name, slug)))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText());
    }
}
