package com.agentbench.platform.rls;

import com.agentbench.support.PostgresTestSupport;
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

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * FORCE RLS on {@code benchmarks.test_suites} and {@code evaluations.flake_quarantines}
 * — the suite / flake-quarantine slice beyond projects, benchmarks, and secrets/repos/snapshots.
 * Proves cross-org SELECT/UPDATE/DELETE are blocked under {@code agentbench_app}
 * even when a query omits the application {@code org_id} filter.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class SuiteFlakeQuarantineRlsIsolationTest {

    @Container
    static PostgreSQLContainer<?> postgres = PostgresTestSupport.postgres();

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        PostgresTestSupport.registerRlsAwareDatasource(registry, postgres);
    }

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    NamedParameterJdbcTemplate jdbc;

    @Test
    void rlsBlocksCrossOrgSelectUpdateDeleteOnSuitesAndFlakeQuarantines() throws Exception {
        String tokenA = register("rls-sfq-a");
        String tokenB = register("rls-sfq-b");
        UUID orgA = createOrg(tokenA);
        UUID orgB = createOrg(tokenB);

        UUID suiteA = createVisibleSuite(tokenA, orgA, "suite-a");
        UUID suiteB = createVisibleSuite(tokenB, orgB, "suite-b");
        UUID quarantineA = createFlakeQuarantine(tokenA, orgA, suiteA, "case.a");
        UUID quarantineB = createFlakeQuarantine(tokenB, orgB, suiteB, "case.b");

        assertSelectIsolation("benchmarks.test_suites", orgA, suiteA, suiteB);
        assertSelectIsolation("evaluations.flake_quarantines", orgA, quarantineA, quarantineB);

        assertUpdateBlocked(
                orgA,
                "UPDATE benchmarks.test_suites SET entry_command = 'pwned' WHERE id = :id",
                suiteB);
        assertUpdateBlocked(
                orgA,
                "UPDATE evaluations.flake_quarantines SET reason = 'pwned' WHERE id = :id",
                quarantineB);

        RlsContext.runWithOrg(orgB, () -> {
            assertEquals(
                    "mvn -q test",
                    scalar("SELECT entry_command FROM benchmarks.test_suites WHERE id = :id", suiteB, String.class));
            assertEquals(
                    "known flake",
                    scalar("SELECT reason FROM evaluations.flake_quarantines WHERE id = :id", quarantineB, String.class));
        });

        assertDeleteBlocked(orgA, "DELETE FROM benchmarks.test_suites WHERE id = :id", suiteB);
        assertDeleteBlocked(orgA, "DELETE FROM evaluations.flake_quarantines WHERE id = :id", quarantineB);

        RlsContext.runWithOrg(orgB, () -> {
            assertEquals(1, countById("benchmarks.test_suites", suiteB));
            assertEquals(1, countById("evaluations.flake_quarantines", quarantineB));
        });

        RlsContext.clear();
        assertEquals(0, countAll("benchmarks.test_suites"), "No suite rows without app.current_org_id or bypass");
        assertEquals(
                0,
                countAll("evaluations.flake_quarantines"),
                "No flake quarantine rows without org GUC");
    }

    private void assertSelectIsolation(String table, UUID orgA, UUID ownId, UUID otherId) {
        RlsContext.runWithOrg(orgA, () -> {
            List<UUID> leaked = selectById(table, otherId);
            assertTrue(leaked.isEmpty(), "RLS must hide other-org rows from SELECT on " + table);
            assertEquals(List.of(ownId), selectById(table, ownId), "RLS must allow the org's own row on " + table);
        });
    }

    private void assertUpdateBlocked(UUID orgId, String sql, UUID targetId) {
        RlsContext.runWithOrg(orgId, () -> {
            int updated = jdbc.update(sql, new MapSqlParameterSource("id", targetId));
            assertEquals(0, updated, "RLS must block cross-org UPDATE: " + sql);
        });
    }

    private void assertDeleteBlocked(UUID orgId, String sql, UUID targetId) {
        RlsContext.runWithOrg(orgId, () -> {
            int deleted = jdbc.update(sql, new MapSqlParameterSource("id", targetId));
            assertEquals(0, deleted, "RLS must block cross-org DELETE: " + sql);
        });
    }

    private List<UUID> selectById(String table, UUID id) {
        return jdbc.query(
                "SELECT id FROM " + table + " WHERE id = :id",
                new MapSqlParameterSource("id", id),
                (rs, rowNum) -> rs.getObject("id", UUID.class));
    }

    private <T> T scalar(String sql, UUID id, Class<T> type) {
        return jdbc.queryForObject(sql, new MapSqlParameterSource("id", id), type);
    }

    private int countById(String table, UUID id) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE id = :id",
                new MapSqlParameterSource("id", id),
                Integer.class);
        return count == null ? 0 : count;
    }

    private int countAll(String table) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Map.of(), Integer.class);
        return count == null ? 0 : count;
    }

    private String register(String label) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "email", label + "-" + UUID.randomUUID() + "@example.com",
                                "password", "password123",
                                "displayName", label))))
                .andReturn();
        assertStatus(result, 201);
        return read(result).path("accessToken").asText();
    }

    private UUID createOrg(String token) throws Exception {
        String slug = "sfq-" + UUID.randomUUID().toString().substring(0, 8);
        MvcResult result = mockMvc.perform(post("/api/v1/orgs")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "SFQ " + slug, "slug", slug))))
                .andReturn();
        assertStatus(result, 201);
        return UUID.fromString(read(result).path("id").asText());
    }

    private UUID createVisibleSuite(String token, UUID orgId, String taskSlug) throws Exception {
        UUID benchmarkId = createBenchmark(token, orgId);
        UUID versionId = createVersion(token, orgId, benchmarkId);
        UUID taskId = createTask(token, orgId, versionId, taskSlug);
        MvcResult result = mockMvc.perform(put("/api/v1/orgs/{orgId}/tasks/{taskId}/suites/visible", orgId, taskId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "framework", "junit",
                                "entryCommand", "mvn -q test",
                                "fixtureContent", "@Test void visible() {}",
                                "caseCount", 1))))
                .andReturn();
        assertStatus(result, 200);
        return UUID.fromString(read(result).path("id").asText());
    }

    private UUID createBenchmark(String token, UUID orgId) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/orgs/{orgId}/benchmarks", orgId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "SFQ Bench", "description", "rls"))))
                .andReturn();
        assertStatus(result, 201);
        return UUID.fromString(read(result).path("id").asText());
    }

    private UUID createVersion(String token, UUID orgId, UUID benchmarkId) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/orgs/{orgId}/benchmarks/{benchmarkId}/versions", orgId, benchmarkId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("version", "1.0.0", "changelog", "rls"))))
                .andReturn();
        assertStatus(result, 201);
        return UUID.fromString(read(result).path("id").asText());
    }

    private UUID createTask(String token, UUID orgId, UUID versionId, String slug) throws Exception {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("slug", slug);
        body.put("title", "SFQ task");
        body.put("prompt", "Fix it");
        body.put("acceptanceCriteria", "tests pass");
        body.put("category", "bugfix");
        body.put("difficulty", "MEDIUM");
        body.put("languageTags", List.of("java"));
        body.put("baselineCommitSha", "0123456789abcdef0123456789abcdef01234567");
        body.put("referencePatch", "diff --git a/x b/x\n");
        body.put("expectedBehavior", "no NPE");
        body.put("timeoutSec", 900);
        body.put("memoryMb", 1024);
        body.put("evalConfig", Map.of("toolchain", "maven"));
        MvcResult result = mockMvc.perform(post("/api/v1/orgs/{orgId}/benchmark-versions/{versionId}/tasks", orgId, versionId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(body)))
                .andReturn();
        assertStatus(result, 201);
        return UUID.fromString(read(result).path("id").asText());
    }

    private UUID createFlakeQuarantine(String token, UUID orgId, UUID suiteId, String caseKey) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/orgs/{orgId}/flake-quarantines", orgId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "suiteId", suiteId,
                                "caseKey", caseKey,
                                "reason", "known flake"))))
                .andReturn();
        assertStatus(result, 201);
        return UUID.fromString(read(result).path("id").asText());
    }

    private static void assertStatus(MvcResult result, int expected) {
        int actual = result.getResponse().getStatus();
        if (actual != expected) {
            String body;
            try {
                body = result.getResponse().getContentAsString();
            } catch (Exception ex) {
                body = "<unreadable>";
            }
            throw new AssertionError("expected HTTP " + expected + " but got " + actual + ": " + body);
        }
    }

    private JsonNode read(MvcResult result) throws Exception {
        String body = result.getResponse().getContentAsString();
        if (body == null || body.isBlank()) {
            return objectMapper.createObjectNode();
        }
        return objectMapper.readTree(body);
    }

    private byte[] json(Map<String, ?> body) throws Exception {
        return objectMapper.writeValueAsBytes(body);
    }
}
