package com.agentbench.evaluation;

import com.agentbench.evaluation.domain.EvaluationState;
import com.agentbench.evaluation.service.EvaluationSyncRunner;
import com.agentbench.evaluation.worker.EvaluationTimeoutWatchdog;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
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

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Milestone 9 recovery scenarios: invalid baseline, mid-flight cancel, timeout watchdog.
 * DLQ topology + retry exhaustion are covered by dedicated unit tests.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class EvaluationRecoveryScenariosTest {

    /** Workspace already contains the "fix" — target PASSes at baseline → not reproducible. */
    private static final String INVALID_BASELINE_FIXTURE = """
            {
              "workspace": {
                "src/Bug.java": "class Bug { String value; String get() { return java.util.Objects.requireNonNull(value); } }\\n"
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

    private static final String VALID_FIXTURE = """
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
                },
                {
                  "key": "control.stillCompiles",
                  "role": "control",
                  "baselineChecks": [
                    {"type": "file_contains", "path": "src/Bug.java", "needle": "class Bug", "expectPresent": true}
                  ],
                  "postChecks": [
                    {"type": "file_contains", "path": "src/Bug.java", "needle": "class Bug", "expectPresent": true}
                  ]
                }
              ]
            }
            """;

    private static final String REFERENCE_PATCH = """
            diff --git a/src/Bug.java b/src/Bug.java
            --- a/src/Bug.java
            +++ b/src/Bug.java
            @@ -1 +1 @@
            -class Bug { String value; String get() { return value; } }
            +class Bug { String value; String get() { return java.util.Objects.requireNonNull(value); } }
            """;

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("agentbench")
            .withUsername("agentbench")
            .withPassword("agentbench");

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.autoconfigure.exclude", () -> String.join(",",
                "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration",
                "org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration",
                "org.springframework.boot.autoconfigure.amqp.RabbitAutoConfiguration"));
        registry.add("management.health.redis.enabled", () -> "false");
        registry.add("management.health.rabbit.enabled", () -> "false");
        registry.add("spring.rabbitmq.listener.simple.auto-startup", () -> "false");
        registry.add("agentbench.evaluation.listener-enabled", () -> "false");
        registry.add("agentbench.jwt.secret", () -> "test-only-jwt-secret-key-32chars-min!!");
        registry.add("agentbench.secrets.encryption-key", () -> "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=");
        registry.add("agentbench.github.validation-enabled", () -> "false");
        registry.add("agentbench.artifacts.root", () -> System.getProperty("java.io.tmpdir") + "/agentbench-m9-recovery-it");
        registry.add("logging.structured.format.console", () -> "");
    }

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    EvaluationSyncRunner syncRunner;

    @Autowired
    EvaluationTimeoutWatchdog timeoutWatchdog;

    @Autowired
    NamedParameterJdbcTemplate jdbc;

    @Test
    void invalidBaselineFailsWithoutRetryingAgent() throws Exception {
        String token = register("baseline-" + UUID.randomUUID() + "@example.com", "Baseline");
        UUID orgId = createOrg(token, "Baseline Org " + UUID.randomUUID(), "bl-" + UUID.randomUUID().toString().substring(0, 8));
        Fixture ids = seedPublishedBenchmark(token, orgId, INVALID_BASELINE_FIXTURE);
        UUID agentId = createFakeAgent(token, orgId, REFERENCE_PATCH);

        MvcResult created = mockMvc.perform(post("/api/v1/orgs/{orgId}/evaluations", orgId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"benchmarkVersionId":"%s","taskId":"%s","agentId":"%s"}
                                """.formatted(ids.versionId(), ids.taskId(), agentId)))
                .andExpect(status().isCreated())
                .andReturn();
        UUID evaluationId = UUID.fromString(objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asText());

        syncRunner.drainAndProcess(20);

        mockMvc.perform(get("/api/v1/orgs/{orgId}/evaluations/{id}", orgId, evaluationId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.evaluation.state").value("FAILED"))
                .andExpect(jsonPath("$.evaluation.failureClass").value("BASELINE"))
                .andExpect(jsonPath("$.evaluation.errorCode").value("BASELINE_NOT_REPRODUCIBLE"));
    }

    @Test
    void midFlightCancelHonoredBetweenStages() throws Exception {
        String token = register("cancel-mf-" + UUID.randomUUID() + "@example.com", "CancelMF");
        UUID orgId = createOrg(token, "Cancel MF " + UUID.randomUUID(), "cm-" + UUID.randomUUID().toString().substring(0, 8));
        Fixture ids = seedPublishedBenchmark(token, orgId, VALID_FIXTURE);
        UUID agentId = createFakeAgent(token, orgId, REFERENCE_PATCH);

        MvcResult created = mockMvc.perform(post("/api/v1/orgs/{orgId}/evaluations", orgId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"benchmarkVersionId":"%s","taskId":"%s","agentId":"%s"}
                                """.formatted(ids.versionId(), ids.taskId(), agentId)))
                .andExpect(status().isCreated())
                .andReturn();
        UUID evaluationId = UUID.fromString(objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asText());

        // One sync cycle claims PREPARING_REPO and enqueues the next stage.
        int first = syncRunner.drainAndProcess(1);
        assertTrue(first >= 1);

        mockMvc.perform(post("/api/v1/orgs/{orgId}/evaluations/{id}/cancel", orgId, evaluationId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cancelRequested").value(true));

        syncRunner.drainAndProcess(10);

        MvcResult detail = mockMvc.perform(get("/api/v1/orgs/{orgId}/evaluations/{id}", orgId, evaluationId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.evaluation.state").value("CANCELLED"))
                .andExpect(jsonPath("$.evaluation.failureClass").value("USER"))
                .andExpect(jsonPath("$.evaluation.errorCode").value("CANCELLED_BY_USER"))
                .andReturn();

        JsonNode body = objectMapper.readTree(detail.getResponse().getContentAsString());
        assertEquals(EvaluationState.CANCELLED.name(), body.path("evaluation").path("state").asText());
    }

    @Test
    void timeoutWatchdogMarksTimedOut() throws Exception {
        String token = register("timeout-" + UUID.randomUUID() + "@example.com", "Timeout");
        UUID orgId = createOrg(token, "Timeout Org " + UUID.randomUUID(), "to-" + UUID.randomUUID().toString().substring(0, 8));
        Fixture ids = seedPublishedBenchmark(token, orgId, VALID_FIXTURE);
        UUID agentId = createFakeAgent(token, orgId, REFERENCE_PATCH);

        MvcResult created = mockMvc.perform(post("/api/v1/orgs/{orgId}/evaluations", orgId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"benchmarkVersionId":"%s","taskId":"%s","agentId":"%s"}
                                """.formatted(ids.versionId(), ids.taskId(), agentId)))
                .andExpect(status().isCreated())
                .andReturn();
        UUID evaluationId = UUID.fromString(objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asText());

        syncRunner.drainAndProcess(1);

        jdbc.update("""
                INSERT INTO evaluations.org_limits
                    (org_id, max_concurrent_evaluations, max_queued_evaluations, max_concurrent_agent_calls,
                     max_duration_sec, max_patch_bytes, max_log_artifact_bytes)
                VALUES
                    (:orgId, 2, 20, 2, 1, 5242880, 20971520)
                ON CONFLICT (org_id) DO UPDATE SET max_duration_sec = EXCLUDED.max_duration_sec
                """, new MapSqlParameterSource("orgId", orgId));

        jdbc.update("""
                UPDATE evaluations.evaluations
                SET started_at = now() - interval '30 seconds'
                WHERE id = :id
                """, new MapSqlParameterSource("id", evaluationId));

        timeoutWatchdog.sweep();

        mockMvc.perform(get("/api/v1/orgs/{orgId}/evaluations/{id}", orgId, evaluationId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.evaluation.state").value("TIMED_OUT"))
                .andExpect(jsonPath("$.evaluation.failureClass").value("INFRASTRUCTURE"))
                .andExpect(jsonPath("$.evaluation.errorCode").value("TIMED_OUT"));
    }

    private Fixture seedPublishedBenchmark(String token, UUID orgId, String fixture) throws Exception {
        UUID benchmarkId = createBenchmark(token, orgId);
        UUID versionId = createVersion(token, orgId, benchmarkId);
        UUID taskId = createTask(token, orgId, versionId);
        upsertVisible(token, orgId, taskId, fixture);
        mockMvc.perform(post("/api/v1/orgs/{orgId}/benchmark-versions/{versionId}/publish", orgId, versionId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        return new Fixture(benchmarkId, versionId, taskId);
    }

    private UUID createFakeAgent(String token, UUID orgId, String patch) throws Exception {
        String escaped = objectMapper.writeValueAsString(patch);
        MvcResult result = mockMvc.perform(post("/api/v1/orgs/{orgId}/agents", orgId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Fake-%s","adapterType":"FAKE","config":{"patch":%s}}
                                """.formatted(UUID.randomUUID().toString().substring(0, 8), escaped)))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText());
    }

    private String register(String email, String name) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"password123","displayName":"%s"}
                                """.formatted(email, name)))
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

    private UUID createBenchmark(String token, UUID orgId) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/orgs/{orgId}/benchmarks", orgId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Recovery Bench %s","description":"m9"}
                                """.formatted(UUID.randomUUID().toString().substring(0, 8))))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText());
    }

    private UUID createVersion(String token, UUID orgId, UUID benchmarkId) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/orgs/{orgId}/benchmarks/{benchmarkId}/versions", orgId, benchmarkId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"version":"1.0.0","changelog":"m9"}
                                """))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText());
    }

    private UUID createTask(String token, UUID orgId, UUID versionId) throws Exception {
        String body = """
                {
                  "slug":"null-safe",
                  "title":"Null-safe getter",
                  "prompt":"Make get() null-safe",
                  "acceptanceCriteria":"requireNonNull",
                  "referencePatch":%s,
                  "timeoutSec":600,
                  "memoryMb":512,
                  "maxPatchBytes":100000,
                  "evalConfig":{"requireTargetFailAtBaseline":true,"allowedPatchPaths":["src/"]}
                }
                """.formatted(objectMapper.writeValueAsString(REFERENCE_PATCH));
        MvcResult result = mockMvc.perform(post("/api/v1/orgs/{orgId}/benchmark-versions/{versionId}/tasks", orgId, versionId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText());
    }

    private void upsertVisible(String token, UUID orgId, UUID taskId, String fixture) throws Exception {
        String body = objectMapper.createObjectNode()
                .put("framework", "fixture-v1")
                .put("entryCommand", "fixture")
                .put("fixtureContent", fixture)
                .put("caseCount", 2)
                .set("expectedOutcomes", objectMapper.createObjectNode())
                .toString();
        mockMvc.perform(put("/api/v1/orgs/{orgId}/tasks/{taskId}/suites/visible", orgId, taskId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());
    }

    private record Fixture(UUID benchmarkId, UUID versionId, UUID taskId) {
    }
}
