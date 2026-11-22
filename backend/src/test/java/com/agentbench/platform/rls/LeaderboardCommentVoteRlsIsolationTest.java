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

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * FORCE RLS on {@code analytics.leaderboard_comments} and {@code analytics.leaderboard_votes}
 * — the public leaderboard engagement slice beyond projects, benchmarks, secrets/repos/snapshots,
 * and suites/flake quarantines.
 * Proves cross-org SELECT/UPDATE/DELETE are blocked under {@code agentbench_app}
 * even when a query omits the application {@code org_id} filter.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class LeaderboardCommentVoteRlsIsolationTest {

    @Container
    static PostgreSQLContainer<?> postgres = PostgresTestSupport.postgres();

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        PostgresTestSupport.registerRlsAwareDatasource(registry, postgres);
        registry.add("agentbench.evaluation.listener-enabled", () -> "false");
        registry.add("agentbench.execution.mode", () -> "local");
        registry.add("agentbench.execution.docker-enabled", () -> "false");
        registry.add("agentbench.artifacts.retention.enabled", () -> "false");
        registry.add("agentbench.public-leaderboards.comment-rate-limit", () -> "100");
        registry.add("agentbench.public-leaderboards.vote-rate-limit", () -> "100");
    }

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    NamedParameterJdbcTemplate jdbc;

    @Test
    void rlsBlocksCrossOrgSelectUpdateDeleteOnCommentsAndVotes() throws Exception {
        String tokenA = register("rls-lcv-a");
        String tokenB = register("rls-lcv-b");
        UUID orgA = createOrg(tokenA, "lcv-a");
        UUID orgB = createOrg(tokenB, "lcv-b");

        String slugA = "lcv-board-a-" + UUID.randomUUID().toString().substring(0, 8);
        String slugB = "lcv-board-b-" + UUID.randomUUID().toString().substring(0, 8);
        seedPublishedBoard(tokenA, orgA, "agent-a", slugA);
        seedPublishedBoard(tokenB, orgB, "agent-b", slugB);

        UUID commentA = postComment(slugA, "comment from A");
        UUID commentB = postComment(slugB, "comment from B");
        UUID voteA = postVoteAndLoadId(orgA, slugA, "anonfp-a-rls-iso");
        UUID voteB = postVoteAndLoadId(orgB, slugB, "anonfp-b-rls-iso");

        assertSelectIsolation("analytics.leaderboard_comments", orgA, commentA, commentB);
        assertSelectIsolation("analytics.leaderboard_votes", orgA, voteA, voteB);

        assertUpdateBlocked(
                orgA,
                "UPDATE analytics.leaderboard_comments SET body = 'pwned' WHERE id = :id",
                commentB);
        assertUpdateBlocked(
                orgA,
                "UPDATE analytics.leaderboard_votes SET value = -1 WHERE id = :id",
                voteB);

        RlsContext.runWithOrg(orgB, () -> {
            assertEquals(
                    "comment from B",
                    scalar("SELECT body FROM analytics.leaderboard_comments WHERE id = :id", commentB, String.class));
            Number voteValue = scalar(
                    "SELECT value FROM analytics.leaderboard_votes WHERE id = :id", voteB, Number.class);
            assertEquals(1, voteValue.intValue());
        });

        assertDeleteBlocked(orgA, "DELETE FROM analytics.leaderboard_comments WHERE id = :id", commentB);
        assertDeleteBlocked(orgA, "DELETE FROM analytics.leaderboard_votes WHERE id = :id", voteB);

        RlsContext.runWithOrg(orgB, () -> {
            assertEquals(1, countById("analytics.leaderboard_comments", commentB));
            assertEquals(1, countById("analytics.leaderboard_votes", voteB));
        });

        RlsContext.clear();
        assertEquals(
                0,
                countAll("analytics.leaderboard_comments"),
                "No comment rows without app.current_org_id or bypass");
        assertEquals(
                0,
                countAll("analytics.leaderboard_votes"),
                "No vote rows without org GUC");
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

    private UUID createOrg(String token, String label) throws Exception {
        String slug = label + "-" + UUID.randomUUID().toString().substring(0, 8);
        MvcResult result = mockMvc.perform(post("/api/v1/orgs")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "LCV " + slug, "slug", slug))))
                .andReturn();
        assertStatus(result, 201);
        return UUID.fromString(read(result).path("id").asText());
    }

    private void seedPublishedBoard(String token, UUID orgId, String agentName, String publicSlug) throws Exception {
        UUID versionId = seedCompletedEvaluation(orgId, agentName);
        MvcResult result = mockMvc.perform(post(
                        "/api/v1/orgs/{orgId}/benchmark-versions/{versionId}/leaderboard/publish",
                        orgId,
                        versionId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("publicSlug", publicSlug, "title", "LCV Board " + agentName))))
                .andReturn();
        assertStatus(result, 200);
    }

    private UUID postComment(String slug, String body) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/public/leaderboards/{slug}/comments", slug)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("body", body, "displayName", "RLS Guest"))))
                .andReturn();
        assertStatus(result, 201);
        return UUID.fromString(read(result).path("id").asText());
    }

    private UUID postVoteAndLoadId(UUID orgId, String slug, String fingerprint) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/public/leaderboards/{slug}/votes", slug)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("value", 1, "voterFingerprint", fingerprint))))
                .andReturn();
        assertStatus(result, 200);
        return RlsContext.callWithOrg(orgId, () -> jdbc.queryForObject(
                """
                        SELECT id FROM analytics.leaderboard_votes
                        WHERE org_id = :orgId AND voter_key = :voterKey
                        """,
                new MapSqlParameterSource()
                        .addValue("orgId", orgId)
                        .addValue("voterKey", "anon:" + fingerprint),
                UUID.class));
    }

    private UUID seedCompletedEvaluation(UUID orgId, String agentName) {
        return RlsContext.callWithOrg(orgId, () -> seedCompletedEvaluationUnderRls(orgId, agentName));
    }

    private UUID seedCompletedEvaluationUnderRls(UUID orgId, String agentName) {
        UUID userId = jdbc.queryForObject(
                "SELECT id FROM identity.users ORDER BY created_at DESC LIMIT 1",
                Map.of(),
                UUID.class);
        UUID benchmarkId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        UUID evaluationId = UUID.randomUUID();
        UUID scoreId = UUID.randomUUID();
        Instant now = Instant.now();

        jdbc.update(
                """
                        INSERT INTO benchmarks.benchmarks (id, org_id, name, description, created_by, created_at, updated_at)
                        VALUES (:id, :orgId, :name, '', :userId, :now, :now)
                        """,
                params(Map.of(
                        "id", benchmarkId,
                        "orgId", orgId,
                        "name", "Bench-" + agentName,
                        "userId", userId,
                        "now", Timestamp.from(now))));
        jdbc.update(
                """
                        INSERT INTO benchmarks.benchmark_versions
                            (id, benchmark_id, org_id, version, status, changelog, scoring_profile_key,
                             published_at, published_by, immutable_snapshot, created_at, updated_at)
                        VALUES
                            (:id, :benchmarkId, :orgId, '1.0.0', 'PUBLISHED', '', 'default-v1',
                             :now, :userId, '{"tasks":[]}'::jsonb, :now, :now)
                        """,
                params(Map.of(
                        "id", versionId,
                        "benchmarkId", benchmarkId,
                        "orgId", orgId,
                        "userId", userId,
                        "now", Timestamp.from(now))));
        jdbc.update(
                """
                        INSERT INTO benchmarks.tasks
                            (id, benchmark_version_id, org_id, slug, title, prompt, acceptance_criteria,
                             reference_patch, expected_behavior, timeout_sec, memory_mb, max_patch_bytes,
                             eval_config_json, ordinal, created_at, updated_at)
                        VALUES
                            (:id, :versionId, :orgId, :slug, :title, 'prompt', 'criteria',
                             '', '', 120, 512, 1048576, '{}'::jsonb, 1, :now, :now)
                        """,
                params(Map.of(
                        "id", taskId,
                        "versionId", versionId,
                        "orgId", orgId,
                        "slug", "task-" + agentName,
                        "title", "Task " + agentName,
                        "now", Timestamp.from(now))));
        jdbc.update(
                """
                        INSERT INTO agents.agents
                            (id, org_id, name, adapter_type, config_json, enabled, created_by, created_at, updated_at)
                        VALUES
                            (:id, :orgId, :name, 'FAKE', '{}'::jsonb, TRUE, :userId, :now, :now)
                        """,
                params(Map.of(
                        "id", agentId,
                        "orgId", orgId,
                        "name", agentName,
                        "userId", userId,
                        "now", Timestamp.from(now))));
        jdbc.update(
                """
                        INSERT INTO evaluations.evaluations
                            (id, org_id, benchmark_version_id, task_id, agent_id, state, attempt,
                             snapshot_json, cancel_requested, created_by, created_at, updated_at,
                             started_at, completed_at)
                        VALUES
                            (:id, :orgId, :versionId, :taskId, :agentId, 'COMPLETED', 1,
                             '{}'::jsonb, FALSE, :userId, :now, :now, :now, :now)
                        """,
                params(Map.of(
                        "id", evaluationId,
                        "orgId", orgId,
                        "versionId", versionId,
                        "taskId", taskId,
                        "agentId", agentId,
                        "userId", userId,
                        "now", Timestamp.from(now))));
        jdbc.update(
                """
                        INSERT INTO evaluations.evaluation_scores
                            (id, evaluation_id, org_id, weighted_total, profile_id, profile_key, score_status,
                             transition_summary_json, raw_summary_json, warnings_json, computed_at)
                        VALUES
                            (:id, :evaluationId, :orgId, :total, 'default-v1', 'default-v1', 'VALID',
                             '{"F2P":1,"P2P":0,"P2F":0,"F2F":0}'::jsonb, '{}'::jsonb, '[]'::jsonb, :now)
                        """,
                new MapSqlParameterSource()
                        .addValue("id", scoreId)
                        .addValue("evaluationId", evaluationId)
                        .addValue("orgId", orgId)
                        .addValue("total", new BigDecimal("91.0000"))
                        .addValue("now", Timestamp.from(now)));
        return versionId;
    }

    private static MapSqlParameterSource params(Map<String, ?> values) {
        MapSqlParameterSource source = new MapSqlParameterSource();
        values.forEach(source::addValue);
        return source;
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
