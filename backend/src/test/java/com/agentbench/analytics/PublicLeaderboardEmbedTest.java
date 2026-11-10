package com.agentbench.analytics;

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
import com.agentbench.platform.rls.RlsContext;
import com.agentbench.support.PostgresTestSupport;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Signed embed mint + validate + cache headers for published public leaderboards.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class PublicLeaderboardEmbedTest {

    @Container
    static PostgreSQLContainer<?> postgres = PostgresTestSupport.postgres();

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        PostgresTestSupport.registerRlsAwareDatasource(registry, postgres);
        registry.add("agentbench.evaluation.listener-enabled", () -> "false");
        registry.add("agentbench.execution.mode", () -> "local");
        registry.add("agentbench.execution.docker-enabled", () -> "false");
        registry.add("agentbench.artifacts.retention.enabled", () -> "false");
        registry.add("agentbench.public-leaderboards.cache-max-age-seconds", () -> "60");
        registry.add("agentbench.public-leaderboards.cache-stale-while-revalidate-seconds", () -> "300");
    }

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    NamedParameterJdbcTemplate jdbc;

    @Test
    void mintValidateRejectAndCacheHeaders() throws Exception {
        String ownerToken = register("embed-owner@example.com", "Embed Owner");
        String viewerToken = register("embed-viewer@example.com", "Embed Viewer");
        UUID orgId = createOrg(ownerToken, "Embed LB Org", "embed-lb-org");

        mockMvc.perform(post("/api/v1/orgs/{orgId}/members", orgId)
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"embed-viewer@example.com","role":"VIEWER"}
                                """))
                .andExpect(status().isCreated());

        Fixture fixture = seedCompletedEvaluation(orgId, "agent-embed", "91.2500", "0.04");

        mockMvc.perform(post("/api/v1/orgs/{orgId}/benchmark-versions/{versionId}/leaderboard/embed-token",
                        orgId, fixture.benchmarkVersionId())
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post(
                        "/api/v1/orgs/{orgId}/benchmark-versions/{versionId}/leaderboard/publish",
                        orgId, fixture.benchmarkVersionId())
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"publicSlug":"embed-demo-board","title":"Embed Demo Board"}
                                """))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/orgs/{orgId}/benchmark-versions/{versionId}/leaderboard/embed-token",
                        orgId, fixture.benchmarkVersionId())
                        .header("Authorization", "Bearer " + viewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden());

        MvcResult mintResult = mockMvc.perform(post(
                        "/api/v1/orgs/{orgId}/benchmark-versions/{versionId}/leaderboard/embed-token",
                        orgId, fixture.benchmarkVersionId())
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"ttlSeconds":3600}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.publicSlug").value("embed-demo-board"))
                .andExpect(jsonPath("$.token").isString())
                .andExpect(jsonPath("$.iframeSnippet", containsString("iframe")))
                .andExpect(jsonPath("$.scriptSnippet", containsString("fetch(")))
                .andExpect(jsonPath("$.embedHtmlPath", containsString("format=html")))
                .andReturn();

        JsonNode minted = objectMapper.readTree(mintResult.getResponse().getContentAsString());
        String token = minted.path("token").asText();

        mockMvc.perform(get("/api/v1/public/leaderboards/{slug}", "embed-demo-board"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", containsString("max-age=60")))
                .andExpect(header().string("Cache-Control", containsString("public")))
                .andExpect(header().exists("ETag"));

        mockMvc.perform(get("/api/v1/public/leaderboards/{slug}/embed", "embed-demo-board")
                        .param("token", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.publicSlug").value("embed-demo-board"))
                .andExpect(jsonPath("$.entries[0].agentName").value("agent-embed"))
                .andExpect(header().string("Cache-Control", containsString("max-age=60")))
                .andExpect(header().exists("ETag"));

        mockMvc.perform(get("/api/v1/public/leaderboards/{slug}/embed", "embed-demo-board")
                        .param("token", token)
                        .param("format", "html"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", containsString("text/html")))
                .andExpect(header().string("Content-Security-Policy", containsString("frame-ancestors")))
                .andExpect(result -> assertTrue(
                        result.getResponse().getContentAsString().contains("Embed Demo Board")));

        mockMvc.perform(get("/api/v1/public/leaderboards/{slug}/embed", "embed-demo-board")
                        .param("token", "v1.not.valid"))
                .andExpect(status().isUnauthorized());

        String[] parts = token.split("\\.");
        String payloadJson = new String(Base64.getUrlDecoder().decode(parts[1]));
        String tamperedPayload = payloadJson.replace("embed-demo-board", "other-board");
        String tampered = parts[0] + "."
                + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(tamperedPayload.getBytes())
                + "." + parts[2];
        mockMvc.perform(get("/api/v1/public/leaderboards/{slug}/embed", "embed-demo-board")
                        .param("token", tampered))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/v1/public/leaderboards/{slug}/embed", "other-board")
                        .param("token", token))
                .andExpect(status().isUnauthorized());
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
