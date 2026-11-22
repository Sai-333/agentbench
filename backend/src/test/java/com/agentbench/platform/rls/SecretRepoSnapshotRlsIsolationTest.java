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
 * FORCE RLS on {@code identity.secret_refs}, {@code projects.project_repositories},
 * and {@code projects.repo_snapshots} — the project/secret slice beyond
 * {@code projects.projects} / {@code benchmarks.benchmarks}.
 * Proves cross-org SELECT/UPDATE/DELETE are blocked under {@code agentbench_app}
 * even when a query omits the application {@code org_id} filter.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class SecretRepoSnapshotRlsIsolationTest {

    private static final String SAMPLE_SHA = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

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
    void rlsBlocksCrossOrgSelectUpdateDeleteOnSecretsReposAndSnapshots() throws Exception {
        String tokenA = register("rls-srs-a");
        String tokenB = register("rls-srs-b");
        UUID orgA = createOrg(tokenA);
        UUID orgB = createOrg(tokenB);
        UUID projectA = createProject(tokenA, orgA, "ProjectA");
        UUID projectB = createProject(tokenB, orgB, "ProjectB");

        UUID secretA = createSecret(tokenA, orgA, "agent-a");
        UUID secretB = createSecret(tokenB, orgB, "agent-b");
        UUID repoA = upsertRepository(tokenA, orgA, projectA, "https://github.com/example/org-a");
        UUID repoB = upsertRepository(tokenB, orgB, projectB, "https://github.com/example/org-b");
        UUID snapshotA = createSnapshot(tokenA, orgA, projectA, "https://github.com/example/org-a");
        UUID snapshotB = createSnapshot(tokenB, orgB, projectB, "https://github.com/example/org-b");

        assertSelectIsolation("identity.secret_refs", orgA, secretA, secretB);
        assertSelectIsolation("projects.project_repositories", orgA, repoA, repoB);
        assertSelectIsolation("projects.repo_snapshots", orgA, snapshotA, snapshotB);

        assertUpdateBlocked(
                orgA,
                "UPDATE identity.secret_refs SET name = 'pwned' WHERE id = :id",
                secretB);
        assertUpdateBlocked(
                orgA,
                "UPDATE projects.project_repositories SET validation_message = 'pwned' WHERE id = :id",
                repoB);
        assertUpdateBlocked(
                orgA,
                "UPDATE projects.repo_snapshots SET content_digest = 'pwned' WHERE id = :id",
                snapshotB);

        RlsContext.runWithOrg(orgB, () -> {
            assertEquals("agent-b", scalar("SELECT name FROM identity.secret_refs WHERE id = :id", secretB, String.class));
            assertEquals(
                    "https://github.com/example/org-b",
                    scalar("SELECT git_url FROM projects.project_repositories WHERE id = :id", repoB, String.class));
            assertEquals(
                    "",
                    scalar("SELECT content_digest FROM projects.repo_snapshots WHERE id = :id", snapshotB, String.class));
        });

        assertDeleteBlocked(orgA, "DELETE FROM identity.secret_refs WHERE id = :id", secretB);
        assertDeleteBlocked(orgA, "DELETE FROM projects.project_repositories WHERE id = :id", repoB);
        assertDeleteBlocked(orgA, "DELETE FROM projects.repo_snapshots WHERE id = :id", snapshotB);

        RlsContext.runWithOrg(orgB, () -> {
            assertEquals(1, countById("identity.secret_refs", secretB));
            assertEquals(1, countById("projects.project_repositories", repoB));
            assertEquals(1, countById("projects.repo_snapshots", snapshotB));
        });

        RlsContext.clear();
        assertEquals(0, countAll("identity.secret_refs"), "No secret rows without app.current_org_id or bypass");
        assertEquals(0, countAll("projects.project_repositories"), "No repository rows without org GUC");
        assertEquals(0, countAll("projects.repo_snapshots"), "No snapshot rows without org GUC");
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
        String slug = "srs-" + UUID.randomUUID().toString().substring(0, 8);
        MvcResult result = mockMvc.perform(post("/api/v1/orgs")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "SRS " + slug, "slug", slug))))
                .andReturn();
        assertStatus(result, 201);
        return UUID.fromString(read(result).path("id").asText());
    }

    private UUID createProject(String token, UUID orgId, String name) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/orgs/{orgId}/projects", orgId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", name, "description", "rls"))))
                .andReturn();
        assertStatus(result, 201);
        return UUID.fromString(read(result).path("id").asText());
    }

    private UUID createSecret(String token, UUID orgId, String name) throws Exception {
        String plaintext = UUID.randomUUID() + "." + UUID.randomUUID();
        MvcResult result = mockMvc.perform(post("/api/v1/orgs/{orgId}/secrets", orgId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "name", name,
                                "purpose", "AGENT",
                                "plaintext", plaintext))))
                .andReturn();
        assertStatus(result, 201);
        String body = result.getResponse().getContentAsString();
        if (body.contains(plaintext) || body.contains("\"ciphertext\"") || body.contains("\"plaintext\"")) {
            throw new AssertionError("secret create echoed secret material");
        }
        return UUID.fromString(read(result).path("id").asText());
    }

    private UUID upsertRepository(String token, UUID orgId, UUID projectId, String gitUrl) throws Exception {
        MvcResult result = mockMvc.perform(put("/api/v1/orgs/{orgId}/projects/{projectId}/repository", orgId, projectId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "gitUrl", gitUrl,
                                "defaultBranch", "main",
                                "validate", false))))
                .andReturn();
        assertStatus(result, 200);
        return UUID.fromString(read(result).path("id").asText());
    }

    private UUID createSnapshot(String token, UUID orgId, UUID projectId, String gitUrl) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/orgs/{orgId}/projects/{projectId}/snapshots", orgId, projectId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "gitUrl", gitUrl,
                                "commitSha", SAMPLE_SHA,
                                "branch", "main",
                                "contentDigest", "",
                                "storageUri", ""))))
                .andReturn();
        assertStatus(result, 201);
        return UUID.fromString(read(result).path("id").asText());
    }

    private static void assertStatus(MvcResult result, int expected) {
        int actual = result.getResponse().getStatus();
        if (actual != expected) {
            throw new AssertionError("expected HTTP " + expected + " but got " + actual);
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
