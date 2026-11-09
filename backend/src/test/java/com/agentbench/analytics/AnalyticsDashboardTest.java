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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Milestone 8: analytics APIs backed by real evaluation/score/invocation rows.
 * Skipped when Docker is unavailable (same pattern as earlier ITs).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class AnalyticsDashboardTest {

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
        registry.add("agentbench.execution.mode", () -> "local");
        registry.add("agentbench.execution.docker-enabled", () -> "false");
        registry.add("agentbench.artifacts.retention.enabled", () -> "false");
    }

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    NamedParameterJdbcTemplate jdbc;

    @Test
    void overviewLeaderboardCostsAndExportUseRealDataAndEnforceOrgIsolation() throws Exception {
        String tokenA = register("analytics-a@example.com", "Analytics A");
        String tokenB = register("analytics-b@example.com", "Analytics B");
        UUID orgA = createOrg(tokenA, "Analytics Org A", "analytics-org-a");
        UUID orgB = createOrg(tokenB, "Analytics Org B", "analytics-org-b");

        Fixture fixtureA = seedCompletedEvaluation(orgA, "agent-alpha", "90.0000", "0.12");
        seedCompletedEvaluation(orgA, "agent-beta", "70.0000", "0.05");

        mockMvc.perform(get("/api/v1/orgs/{orgId}/analytics/overview", orgA)
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalEvaluations").value(2))
                .andExpect(jsonPath("$.completed").value(2))
                .andExpect(jsonPath("$.totalEstimatedCostUsd").value(0.17))
                .andExpect(jsonPath("$.recent.length()").value(2));

        mockMvc.perform(get("/api/v1/orgs/{orgId}/benchmark-versions/{versionId}/leaderboard",
                        orgA, fixtureA.benchmarkVersionId())
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.note").value(org.hamcrest.Matchers.containsString("Organization-private")))
                .andExpect(jsonPath("$.entries[0].weightedTotal").value(90.0))
                .andExpect(jsonPath("$.entries[0].rank").value(1));

        mockMvc.perform(get("/api/v1/orgs/{orgId}/analytics/comparisons", orgA)
                        .param("benchmarkVersionId", fixtureA.benchmarkVersionId().toString())
                        .param("agentIds", fixtureA.agentId().toString())
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows.length()").value(1))
                .andExpect(jsonPath("$.rows[0].weightedTotal").value(90.0));

        mockMvc.perform(get("/api/v1/orgs/{orgId}/analytics/costs", orgA)
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalEstimatedCostUsd").value(0.17))
                .andExpect(jsonPath("$.totalTokens").value(150));

        mockMvc.perform(get("/api/v1/orgs/{orgId}/analytics/evaluations", orgA)
                        .param("q", "agent-alpha")
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.total").value(1))
                .andExpect(jsonPath("$.items[0].agentName").value("agent-alpha"));

        mockMvc.perform(get("/api/v1/orgs/{orgId}/evaluations/{evaluationId}/export",
                        orgA, fixtureA.evaluationId())
                        .param("format", "csv")
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(result -> assertTrue(
                        result.getResponse().getContentAsString().contains("section,key,value")));

        mockMvc.perform(get("/api/v1/orgs/{orgId}/analytics/health", orgA)
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.counts.totalEvaluations").value(2));

        // Org B cannot read Org A analytics.
        mockMvc.perform(get("/api/v1/orgs/{orgId}/analytics/overview", orgA)
                        .header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/v1/orgs/{orgId}/analytics/overview", orgB)
                        .header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalEvaluations").value(0));
    }

    private String register(String email, String displayName) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"Password123!","displayName":"%s"}
                                """.formatted(email, displayName)))
                .andExpect(status().isOk())
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
                             '{"F2P":1,"P2P":1,"P2F":0,"F2F":0}'::jsonb, '{}'::jsonb, '[]'::jsonb, :now)
                        """,
                new MapSqlParameterSource()
                        .addValue("id", scoreId)
                        .addValue("evaluationId", evaluationId)
                        .addValue("orgId", orgId)
                        .addValue("total", new BigDecimal(weightedTotal))
                        .addValue("now", Timestamp.from(now)));
        jdbc.update("""
                        INSERT INTO evaluations.score_metrics
                            (id, evaluation_score_id, metric_key, raw_value, weight, weighted_value, meta_json)
                        VALUES
                            (:id, :scoreId, 'target_failure_resolution', 1.0, 0.30, 30.0, '{}'::jsonb)
                        """,
                params(Map.of("id", UUID.randomUUID(), "scoreId", scoreId)));
        jdbc.update("""
                        INSERT INTO agents.agent_invocations
                            (id, org_id, evaluation_id, agent_id, task_id, provider, model, status,
                             attempt, max_attempts, started_at, finished_at, total_tokens, estimated_cost_usd,
                             meta_json, created_at)
                        VALUES
                            (:id, :orgId, :evaluationId, :agentId, :taskId, 'FAKE', 'fake-v1', 'SUCCEEDED',
                             1, 1, :now, :now, 75, :cost, '{}'::jsonb, :now)
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
