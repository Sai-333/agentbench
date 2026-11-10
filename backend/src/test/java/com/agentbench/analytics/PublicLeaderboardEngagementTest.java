package com.agentbench.analytics;

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
import com.agentbench.platform.rls.RlsContext;
import com.agentbench.support.PostgresTestSupport;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Public board comments + up/down votes: anonymous write on published boards only,
 * OWNER/ADMIN soft-delete moderation, unpublished → 404.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class PublicLeaderboardEngagementTest {

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
    void anonymousCommentsVotesAndModeration() throws Exception {
        String ownerToken = register("engage-owner@example.com", "Engage Owner");
        String viewerToken = register("engage-viewer@example.com", "Engage Viewer");
        UUID orgId = createOrg(ownerToken, "Engage Org", "engage-org");

        mockMvc.perform(post("/api/v1/orgs/{orgId}/members", orgId)
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"engage-viewer@example.com","role":"VIEWER"}
                                """))
                .andExpect(status().isCreated());

        Fixture fixture = seedCompletedEvaluation(orgId, "agent-engage", "91.0000", "0.02");

        mockMvc.perform(post("/api/v1/public/leaderboards/{slug}/comments", "missing-board")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"body":"hello","displayName":"Guest"}
                                """))
                .andExpect(status().isNotFound());

        mockMvc.perform(post(
                        "/api/v1/orgs/{orgId}/benchmark-versions/{versionId}/leaderboard/publish",
                        orgId, fixture.benchmarkVersionId())
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"publicSlug":"engage-public-board","title":"Engage Board"}
                                """))
                .andExpect(status().isOk());

        MvcResult commentResult = mockMvc.perform(post(
                        "/api/v1/public/leaderboards/{slug}/comments", "engage-public-board")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"body":"Looks solid for a portfolio demo.","displayName":"Guest Reader"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.body").value("Looks solid for a portfolio demo."))
                .andExpect(jsonPath("$.displayName").value("Guest Reader"))
                .andExpect(jsonPath("$.authenticatedAuthor").value(false))
                .andReturn();
        UUID commentId = UUID.fromString(
                objectMapper.readTree(commentResult.getResponse().getContentAsString()).path("id").asText());

        mockMvc.perform(get("/api/v1/public/leaderboards/{slug}/comments", "engage-public-board"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.comments[0].id").value(commentId.toString()))
                .andExpect(jsonPath("$.page.total").value(1));

        mockMvc.perform(post("/api/v1/public/leaderboards/{slug}/votes", "engage-public-board")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"value":1,"voterFingerprint":"anonfp12345678"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.upCount").value(1))
                .andExpect(jsonPath("$.downCount").value(0))
                .andExpect(jsonPath("$.score").value(1))
                .andExpect(jsonPath("$.myVote").value(1));

        mockMvc.perform(post("/api/v1/public/leaderboards/{slug}/votes", "engage-public-board")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"value":-1,"voterFingerprint":"anonfp12345678"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.upCount").value(0))
                .andExpect(jsonPath("$.downCount").value(1))
                .andExpect(jsonPath("$.myVote").value(-1));

        mockMvc.perform(post("/api/v1/public/leaderboards/{slug}/votes", "engage-public-board")
                        .header("Authorization", "Bearer " + viewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"value":1,"voterFingerprint":"ignored-when-authed"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.upCount").value(1))
                .andExpect(jsonPath("$.downCount").value(1))
                .andExpect(jsonPath("$.score").value(0));

        mockMvc.perform(delete(
                        "/api/v1/orgs/{orgId}/benchmark-versions/{versionId}/leaderboard/comments/{commentId}",
                        orgId, fixture.benchmarkVersionId(), commentId)
                        .header("Authorization", "Bearer " + viewerToken))
                .andExpect(status().isForbidden());

        mockMvc.perform(delete(
                        "/api/v1/orgs/{orgId}/benchmark-versions/{versionId}/leaderboard/comments/{commentId}",
                        orgId, fixture.benchmarkVersionId(), commentId)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/public/leaderboards/{slug}/comments", "engage-public-board"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.total").value(0));

        MvcResult embedToken = mockMvc.perform(post(
                        "/api/v1/orgs/{orgId}/benchmark-versions/{versionId}/leaderboard/embed-token",
                        orgId, fixture.benchmarkVersionId())
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andReturn();
        String token = objectMapper.readTree(embedToken.getResponse().getContentAsString())
                .path("token").asText();

        String html = mockMvc.perform(get("/api/v1/public/leaderboards/{slug}/embed", "engage-public-board")
                        .param("token", token)
                        .param("format", "html"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        org.junit.jupiter.api.Assertions.assertTrue(html.contains("Votes:"));
        org.junit.jupiter.api.Assertions.assertTrue(html.contains("Comment or vote on the public page"));

        mockMvc.perform(post(
                        "/api/v1/orgs/{orgId}/benchmark-versions/{versionId}/leaderboard/unpublish",
                        orgId, fixture.benchmarkVersionId())
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/public/leaderboards/{slug}/comments", "engage-public-board"))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/v1/public/leaderboards/{slug}/votes", "engage-public-board")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"value":1,"voterFingerprint":"anonfp12345678"}
                                """))
                .andExpect(status().isNotFound());
    }

    @Test
    void commentValidationAndOrgPrivateStayPrivate() throws Exception {
        String ownerToken = register("private-engage@example.com", "Private Engage");
        UUID orgId = createOrg(ownerToken, "Private Engage Org", "private-engage-org");
        Fixture fixture = seedCompletedEvaluation(orgId, "agent-private", "70.0000", "0.01");

        // Never published — org-private leaderboard still works; public engagement 404s.
        mockMvc.perform(get("/api/v1/orgs/{orgId}/benchmark-versions/{versionId}/leaderboard",
                        orgId, fixture.benchmarkVersionId())
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.note", containsString("Organization-private")));

        mockMvc.perform(post("/api/v1/public/leaderboards/{slug}/comments", "private-engage-org-bench")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"body":"nope"}
                                """))
                .andExpect(status().isNotFound());

        mockMvc.perform(post(
                        "/api/v1/orgs/{orgId}/benchmark-versions/{versionId}/leaderboard/publish",
                        orgId, fixture.benchmarkVersionId())
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"publicSlug":"validation-board","title":"Validation"}
                                """))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/public/leaderboards/{slug}/comments", "validation-board")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"body":""}
                                """))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/v1/public/leaderboards/{slug}/votes", "validation-board")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"value":2,"voterFingerprint":"anonfp12345678"}
                                """))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/v1/public/leaderboards/{slug}/votes", "validation-board")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"value":1}
                                """))
                .andExpect(status().isBadRequest());
    }

    private String register(String email, String displayName) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"Password123!","displayName":"%s"}
                                """.formatted(email, displayName)))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("accessToken").asText();
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
        return UUID.fromString(objectMapper.readTree(result.getResponse().getContentAsString()).path("id").asText());
    }

    private Fixture seedCompletedEvaluation(
            UUID orgId,
            String agentName,
            String weightedTotal,
            String costUsd) {
        return RlsContext.callWithOrg(orgId, () -> seedCompletedEvaluationUnderRls(
                orgId, agentName, weightedTotal, costUsd));
    }

    private Fixture seedCompletedEvaluationUnderRls(
            UUID orgId,
            String agentName,
            String weightedTotal,
            String costUsd) {
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

        jdbc.update("""
                        INSERT INTO benchmarks.benchmarks (id, org_id, name, description, created_by, created_at, updated_at)
                        VALUES (:id, :orgId, :name, '', :userId, :now, :now)
                        """,
                params(Map.of(
                        "id", benchmarkId,
                        "orgId", orgId,
                        "name", "Bench-" + agentName,
                        "userId", userId,
                        "now", Timestamp.from(now)
                )));
        jdbc.update("""
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
                        "now", Timestamp.from(now)
                )));
        jdbc.update("""
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
                        "now", Timestamp.from(now)
                )));
        jdbc.update("""
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
                        "now", Timestamp.from(now)
                )));
        jdbc.update("""
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
                        "now", Timestamp.from(now)
                )));
        jdbc.update("""
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
                        .addValue("total", new BigDecimal(weightedTotal))
                        .addValue("now", Timestamp.from(now)));
        jdbc.update("""
                        INSERT INTO agents.agent_invocations
                            (id, org_id, evaluation_id, agent_id, task_id, provider, model, status,
                             attempt, max_attempts, started_at, finished_at, total_tokens, estimated_cost_usd,
                             meta_json, created_at)
                        VALUES
                            (:id, :orgId, :evaluationId, :agentId, :taskId, 'FAKE', 'fake-v1', 'SUCCEEDED',
                             1, 1, :now, :now, 42, :cost, '{}'::jsonb, :now)
                        """,
                new MapSqlParameterSource()
                        .addValue("id", UUID.randomUUID())
                        .addValue("orgId", orgId)
                        .addValue("evaluationId", evaluationId)
                        .addValue("agentId", agentId)
                        .addValue("taskId", taskId)
                        .addValue("now", Timestamp.from(now))
                        .addValue("cost", new BigDecimal(costUsd)));
        return new Fixture(orgId, versionId, taskId, agentId, evaluationId);
    }

    private static MapSqlParameterSource params(Map<String, ?> values) {
        MapSqlParameterSource source = new MapSqlParameterSource();
        values.forEach(source::addValue);
        return source;
    }

    private record Fixture(
            UUID orgId,
            UUID benchmarkVersionId,
            UUID taskId,
            UUID agentId,
            UUID evaluationId
    ) {
    }
}
