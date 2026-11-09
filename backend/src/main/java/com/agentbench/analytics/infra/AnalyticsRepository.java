package com.agentbench.analytics.infra;

import com.agentbench.analytics.api.dto.AnalyticsDtos;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Repository
public class AnalyticsRepository {

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public AnalyticsRepository(NamedParameterJdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public Map<String, Long> countsByState(UUID orgId) {
        List<Map.Entry<String, Long>> rows = jdbc.query("""
                        SELECT state, COUNT(*) AS cnt
                        FROM evaluations.evaluations
                        WHERE org_id = :orgId
                        GROUP BY state
                        """,
                new MapSqlParameterSource("orgId", orgId),
                (rs, rowNum) -> Map.entry(rs.getString("state"), rs.getLong("cnt")));
        Map<String, Long> result = new HashMap<>();
        for (Map.Entry<String, Long> entry : rows) {
            result.put(entry.getKey(), entry.getValue());
        }
        return result;
    }

    public Double averageWeightedTotal(UUID orgId) {
        return jdbc.query("""
                        SELECT AVG(s.weighted_total) AS avg_total
                        FROM evaluations.evaluation_scores s
                        JOIN evaluations.evaluations e ON e.id = s.evaluation_id
                        WHERE s.org_id = :orgId
                          AND e.state = 'COMPLETED'
                          AND s.score_status = 'VALID'
                          AND s.weighted_total IS NOT NULL
                        """,
                new MapSqlParameterSource("orgId", orgId),
                rs -> {
                    if (!rs.next()) {
                        return null;
                    }
                    BigDecimal avg = rs.getBigDecimal("avg_total");
                    return avg == null ? null : avg.doubleValue();
                });
    }

    public BigDecimal totalEstimatedCost(UUID orgId) {
        return jdbc.query("""
                        SELECT COALESCE(SUM(estimated_cost_usd), 0) AS total_cost
                        FROM agents.agent_invocations
                        WHERE org_id = :orgId
                        """,
                new MapSqlParameterSource("orgId", orgId),
                rs -> {
                    rs.next();
                    BigDecimal value = rs.getBigDecimal("total_cost");
                    return value == null ? BigDecimal.ZERO : value;
                });
    }

    public Long totalTokens(UUID orgId) {
        return jdbc.query("""
                        SELECT COALESCE(SUM(total_tokens), 0) AS total_tokens
                        FROM agents.agent_invocations
                        WHERE org_id = :orgId
                        """,
                new MapSqlParameterSource("orgId", orgId),
                rs -> {
                    rs.next();
                    return rs.getLong("total_tokens");
                });
    }

    public List<AnalyticsDtos.RecentEvaluationSummary> recentEvaluations(UUID orgId, int limit) {
        return jdbc.query("""
                        SELECT e.id, e.state, e.agent_id, a.name AS agent_name, e.task_id, t.slug AS task_slug,
                               e.benchmark_version_id, s.weighted_total, e.created_at, e.completed_at
                        FROM evaluations.evaluations e
                        JOIN agents.agents a ON a.id = e.agent_id
                        JOIN benchmarks.tasks t ON t.id = e.task_id
                        LEFT JOIN evaluations.evaluation_scores s ON s.evaluation_id = e.id
                        WHERE e.org_id = :orgId
                        ORDER BY e.created_at DESC
                        LIMIT :limit
                        """,
                new MapSqlParameterSource()
                        .addValue("orgId", orgId)
                        .addValue("limit", Math.min(Math.max(limit, 1), 50)),
                (rs, rowNum) -> new AnalyticsDtos.RecentEvaluationSummary(
                        rs.getObject("id", UUID.class),
                        rs.getString("state"),
                        rs.getObject("agent_id", UUID.class),
                        rs.getString("agent_name"),
                        rs.getObject("task_id", UUID.class),
                        rs.getString("task_slug"),
                        rs.getObject("benchmark_version_id", UUID.class),
                        rs.getBigDecimal("weighted_total"),
                        toInstant(rs, "created_at"),
                        toInstant(rs, "completed_at")
                ));
    }

    public List<AnalyticsDtos.DailyVolumePoint> volumeByDay(UUID orgId, int days) {
        int window = Math.min(Math.max(days, 1), 90);
        return jdbc.query("""
                        SELECT day::text AS day,
                               SUM(started)::bigint AS started,
                               SUM(completed)::bigint AS completed
                        FROM (
                            SELECT DATE(created_at) AS day, 1 AS started, 0 AS completed
                            FROM evaluations.evaluations
                            WHERE org_id = :orgId
                              AND created_at >= (CURRENT_DATE - (:days - 1))
                            UNION ALL
                            SELECT DATE(completed_at) AS day, 0 AS started, 1 AS completed
                            FROM evaluations.evaluations
                            WHERE org_id = :orgId
                              AND completed_at IS NOT NULL
                              AND completed_at >= (CURRENT_DATE - (:days - 1))
                        ) daily
                        GROUP BY day
                        ORDER BY day ASC
                        """,
                new MapSqlParameterSource()
                        .addValue("orgId", orgId)
                        .addValue("days", window),
                (rs, rowNum) -> new AnalyticsDtos.DailyVolumePoint(
                        rs.getString("day"),
                        rs.getLong("started"),
                        rs.getLong("completed")
                ));
    }

    public long countLeaderboard(UUID orgId, UUID benchmarkVersionId, UUID taskId) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("orgId", orgId)
                .addValue("benchmarkVersionId", benchmarkVersionId);
        StringBuilder sql = new StringBuilder("""
                SELECT COUNT(*) FROM (
                    SELECT DISTINCT ON (e.agent_id, e.task_id) e.id
                    FROM evaluations.evaluations e
                    JOIN evaluations.evaluation_scores s ON s.evaluation_id = e.id
                    WHERE e.org_id = :orgId
                      AND e.benchmark_version_id = :benchmarkVersionId
                      AND e.state = 'COMPLETED'
                      AND s.score_status = 'VALID'
                      AND s.weighted_total IS NOT NULL
                """);
        if (taskId != null) {
            sql.append(" AND e.task_id = :taskId");
            params.addValue("taskId", taskId);
        }
        sql.append("""
                    ORDER BY e.agent_id, e.task_id, s.weighted_total DESC, e.completed_at ASC NULLS LAST
                ) ranked
                """);
        Long count = jdbc.queryForObject(sql.toString(), params, Long.class);
        return count == null ? 0L : count;
    }

    public List<AnalyticsDtos.LeaderboardEntry> leaderboard(
            UUID orgId,
            UUID benchmarkVersionId,
            UUID taskId,
            int limit,
            int offset) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("orgId", orgId)
                .addValue("benchmarkVersionId", benchmarkVersionId)
                .addValue("limit", Math.min(Math.max(limit, 1), 100))
                .addValue("offset", Math.max(offset, 0));
        StringBuilder sql = new StringBuilder("""
                SELECT * FROM (
                    SELECT DISTINCT ON (e.agent_id, e.task_id)
                           e.id AS evaluation_id,
                           e.agent_id,
                           a.name AS agent_name,
                           a.adapter_type,
                           e.task_id,
                           t.slug AS task_slug,
                           s.weighted_total,
                           s.score_status,
                           s.transition_summary_json,
                           e.completed_at,
                           e.started_at,
                           inv.total_tokens,
                           inv.estimated_cost_usd
                    FROM evaluations.evaluations e
                    JOIN evaluations.evaluation_scores s ON s.evaluation_id = e.id
                    JOIN agents.agents a ON a.id = e.agent_id
                    JOIN benchmarks.tasks t ON t.id = e.task_id
                    LEFT JOIN LATERAL (
                        SELECT SUM(total_tokens) AS total_tokens,
                               SUM(estimated_cost_usd) AS estimated_cost_usd
                        FROM agents.agent_invocations ai
                        WHERE ai.evaluation_id = e.id
                    ) inv ON TRUE
                    WHERE e.org_id = :orgId
                      AND e.benchmark_version_id = :benchmarkVersionId
                      AND e.state = 'COMPLETED'
                      AND s.score_status = 'VALID'
                      AND s.weighted_total IS NOT NULL
                """);
        if (taskId != null) {
            sql.append(" AND e.task_id = :taskId");
            params.addValue("taskId", taskId);
        }
        sql.append("""
                    ORDER BY e.agent_id, e.task_id, s.weighted_total DESC, e.completed_at ASC NULLS LAST
                ) best
                ORDER BY weighted_total DESC, completed_at ASC NULLS LAST
                LIMIT :limit OFFSET :offset
                """);
        List<AnalyticsDtos.LeaderboardEntry> rows = jdbc.query(sql.toString(), params, (rs, rowNum) -> {
            Instant started = toInstant(rs, "started_at");
            Instant completed = toInstant(rs, "completed_at");
            Long durationMs = null;
            if (started != null && completed != null) {
                durationMs = completed.toEpochMilli() - started.toEpochMilli();
            }
            Number tokens = (Number) rs.getObject("total_tokens");
            return new AnalyticsDtos.LeaderboardEntry(
                    0,
                    rs.getObject("agent_id", UUID.class),
                    rs.getString("agent_name"),
                    rs.getString("adapter_type"),
                    rs.getObject("task_id", UUID.class),
                    rs.getString("task_slug"),
                    rs.getObject("evaluation_id", UUID.class),
                    rs.getBigDecimal("weighted_total"),
                    rs.getString("score_status"),
                    readIntMap(rs.getString("transition_summary_json")),
                    completed,
                    durationMs,
                    tokens == null ? null : tokens.longValue(),
                    rs.getBigDecimal("estimated_cost_usd")
            );
        });
        for (int i = 0; i < rows.size(); i++) {
            AnalyticsDtos.LeaderboardEntry e = rows.get(i);
            rows.set(i, new AnalyticsDtos.LeaderboardEntry(
                    offset + i + 1,
                    e.agentId(), e.agentName(), e.adapterType(), e.taskId(), e.taskSlug(),
                    e.evaluationId(), e.weightedTotal(), e.scoreStatus(), e.transitionSummary(),
                    e.completedAt(), e.durationMs(), e.totalTokens(), e.estimatedCostUsd()
            ));
        }
        return rows;
    }

    public String scoringProfileKey(UUID orgId, UUID benchmarkVersionId) {
        List<String> keys = jdbc.query("""
                        SELECT scoring_profile_key
                        FROM benchmarks.benchmark_versions
                        WHERE org_id = :orgId AND id = :id
                        """,
                new MapSqlParameterSource()
                        .addValue("orgId", orgId)
                        .addValue("id", benchmarkVersionId),
                (rs, rowNum) -> rs.getString("scoring_profile_key"));
        return keys.stream().findFirst().orElse("default-v1");
    }

    public boolean benchmarkVersionExists(UUID orgId, UUID benchmarkVersionId) {
        Integer count = jdbc.queryForObject("""
                        SELECT COUNT(*) FROM benchmarks.benchmark_versions
                        WHERE org_id = :orgId AND id = :id
                        """,
                new MapSqlParameterSource()
                        .addValue("orgId", orgId)
                        .addValue("id", benchmarkVersionId),
                Integer.class);
        return count != null && count > 0;
    }

    public List<AnalyticsDtos.ComparisonRow> comparisons(
            UUID orgId,
            UUID benchmarkVersionId,
            List<UUID> agentIds,
            UUID taskId) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("orgId", orgId)
                .addValue("benchmarkVersionId", benchmarkVersionId)
                .addValue("agentIds", agentIds);
        StringBuilder sql = new StringBuilder("""
                SELECT DISTINCT ON (e.agent_id, e.task_id)
                       e.agent_id,
                       a.name AS agent_name,
                       a.adapter_type,
                       e.task_id,
                       t.slug AS task_slug,
                       e.id AS evaluation_id,
                       s.weighted_total,
                       s.transition_summary_json,
                       e.completed_at,
                       s.id AS score_id
                FROM evaluations.evaluations e
                JOIN evaluations.evaluation_scores s ON s.evaluation_id = e.id
                JOIN agents.agents a ON a.id = e.agent_id
                JOIN benchmarks.tasks t ON t.id = e.task_id
                WHERE e.org_id = :orgId
                  AND e.benchmark_version_id = :benchmarkVersionId
                  AND e.agent_id IN (:agentIds)
                  AND e.state = 'COMPLETED'
                  AND s.score_status = 'VALID'
                  AND s.weighted_total IS NOT NULL
                """);
        if (taskId != null) {
            sql.append(" AND e.task_id = :taskId");
            params.addValue("taskId", taskId);
        }
        sql.append(" ORDER BY e.agent_id, e.task_id, s.weighted_total DESC, e.completed_at ASC NULLS LAST");

        List<RawComparison> raw = jdbc.query(sql.toString(), params, (rs, rowNum) -> new RawComparison(
                rs.getObject("agent_id", UUID.class),
                rs.getString("agent_name"),
                rs.getString("adapter_type"),
                rs.getObject("task_id", UUID.class),
                rs.getString("task_slug"),
                rs.getObject("evaluation_id", UUID.class),
                rs.getBigDecimal("weighted_total"),
                readIntMap(rs.getString("transition_summary_json")),
                toInstant(rs, "completed_at"),
                rs.getObject("score_id", UUID.class)
        ));

        return raw.stream().map(r -> new AnalyticsDtos.ComparisonRow(
                r.agentId(),
                r.agentName(),
                r.adapterType(),
                r.taskId(),
                r.taskSlug(),
                r.evaluationId(),
                r.weightedTotal(),
                metricRaws(r.scoreId()),
                r.transitionSummary(),
                r.completedAt()
        )).toList();
    }

    private Map<String, BigDecimal> metricRaws(UUID scoreId) {
        List<Map.Entry<String, BigDecimal>> rows = jdbc.query("""
                        SELECT metric_key, raw_value
                        FROM evaluations.score_metrics
                        WHERE evaluation_score_id = :scoreId
                        ORDER BY metric_key
                        """,
                new MapSqlParameterSource("scoreId", scoreId),
                (rs, rowNum) -> Map.entry(rs.getString("metric_key"), rs.getBigDecimal("raw_value")));
        Map<String, BigDecimal> map = new HashMap<>();
        for (Map.Entry<String, BigDecimal> entry : rows) {
            map.put(entry.getKey(), entry.getValue());
        }
        return map;
    }

    public CostTotals costTotals(UUID orgId, UUID agentId, Instant from, Instant to) {
        MapSqlParameterSource params = baseCostParams(orgId, agentId, from, to);
        return jdbc.query("""
                        SELECT COUNT(*) AS invocation_count,
                               COALESCE(SUM(prompt_tokens), 0) AS prompt_tokens,
                               COALESCE(SUM(completion_tokens), 0) AS completion_tokens,
                               COALESCE(SUM(total_tokens), 0) AS total_tokens,
                               COALESCE(SUM(estimated_cost_usd), 0) AS total_cost
                        FROM agents.agent_invocations
                        WHERE org_id = :orgId
                        """ + costFilters(agentId, from, to),
                params,
                rs -> {
                    rs.next();
                    return new CostTotals(
                            rs.getLong("invocation_count"),
                            rs.getLong("prompt_tokens"),
                            rs.getLong("completion_tokens"),
                            rs.getLong("total_tokens"),
                            rs.getBigDecimal("total_cost")
                    );
                });
    }

    public List<AnalyticsDtos.CostByAgent> costsByAgent(UUID orgId, UUID agentId, Instant from, Instant to) {
        MapSqlParameterSource params = baseCostParams(orgId, agentId, from, to);
        return jdbc.query("""
                        SELECT a.id AS agent_id, a.name AS agent_name, a.adapter_type,
                               COUNT(i.id) AS invocation_count,
                               COALESCE(SUM(i.total_tokens), 0) AS total_tokens,
                               COALESCE(SUM(i.estimated_cost_usd), 0) AS estimated_cost_usd
                        FROM agents.agents a
                        LEFT JOIN agents.agent_invocations i
                          ON i.agent_id = a.id
                         AND i.org_id = :orgId
                        """ + costJoinFilters(agentId, from, to) + """
                        WHERE a.org_id = :orgId
                        GROUP BY a.id, a.name, a.adapter_type
                        HAVING COUNT(i.id) > 0
                        ORDER BY estimated_cost_usd DESC NULLS LAST, total_tokens DESC
                        """,
                params,
                (rs, rowNum) -> new AnalyticsDtos.CostByAgent(
                        rs.getObject("agent_id", UUID.class),
                        rs.getString("agent_name"),
                        rs.getString("adapter_type"),
                        rs.getLong("invocation_count"),
                        rs.getLong("total_tokens"),
                        rs.getBigDecimal("estimated_cost_usd")
                ));
    }

    public List<AnalyticsDtos.CostByDay> costsByDay(UUID orgId, UUID agentId, Instant from, Instant to) {
        MapSqlParameterSource params = baseCostParams(orgId, agentId, from, to);
        return jdbc.query("""
                        SELECT DATE(created_at)::text AS day,
                               COUNT(*) AS invocation_count,
                               COALESCE(SUM(total_tokens), 0) AS total_tokens,
                               COALESCE(SUM(estimated_cost_usd), 0) AS estimated_cost_usd
                        FROM agents.agent_invocations
                        WHERE org_id = :orgId
                        """ + costFilters(agentId, from, to) + """
                        GROUP BY DATE(created_at)
                        ORDER BY day ASC
                        """,
                params,
                (rs, rowNum) -> new AnalyticsDtos.CostByDay(
                        rs.getString("day"),
                        rs.getLong("invocation_count"),
                        rs.getLong("total_tokens"),
                        rs.getBigDecimal("estimated_cost_usd")
                ));
    }

    public long countEvaluations(
            UUID orgId,
            String state,
            UUID agentId,
            UUID taskId,
            UUID benchmarkVersionId,
            String q) {
        MapSqlParameterSource params = new MapSqlParameterSource("orgId", orgId);
        String sql = """
                SELECT COUNT(*)
                FROM evaluations.evaluations e
                JOIN agents.agents a ON a.id = e.agent_id
                JOIN benchmarks.tasks t ON t.id = e.task_id
                WHERE e.org_id = :orgId
                """ + evaluationSearchFilters(state, agentId, taskId, benchmarkVersionId, q, params);
        Long count = jdbc.queryForObject(sql, params, Long.class);
        return count == null ? 0L : count;
    }

    public List<AnalyticsDtos.EvaluationSearchItem> searchEvaluations(
            UUID orgId,
            String state,
            UUID agentId,
            UUID taskId,
            UUID benchmarkVersionId,
            String q,
            int limit,
            int offset) {
        MapSqlParameterSource params = new MapSqlParameterSource("orgId", orgId)
                .addValue("limit", Math.min(Math.max(limit, 1), 100))
                .addValue("offset", Math.max(offset, 0));
        String sql = """
                SELECT e.id, e.state, e.agent_id, a.name AS agent_name, e.task_id, t.slug AS task_slug,
                       e.benchmark_version_id, bv.version AS benchmark_version, s.weighted_total,
                       e.failure_class, e.created_at, e.updated_at, e.completed_at
                FROM evaluations.evaluations e
                JOIN agents.agents a ON a.id = e.agent_id
                JOIN benchmarks.tasks t ON t.id = e.task_id
                JOIN benchmarks.benchmark_versions bv ON bv.id = e.benchmark_version_id
                LEFT JOIN evaluations.evaluation_scores s ON s.evaluation_id = e.id
                WHERE e.org_id = :orgId
                """ + evaluationSearchFilters(state, agentId, taskId, benchmarkVersionId, q, params)
                + """
                ORDER BY e.created_at DESC
                LIMIT :limit OFFSET :offset
                """;
        return jdbc.query(sql, params, (rs, rowNum) -> new AnalyticsDtos.EvaluationSearchItem(
                rs.getObject("id", UUID.class),
                rs.getString("state"),
                rs.getObject("agent_id", UUID.class),
                rs.getString("agent_name"),
                rs.getObject("task_id", UUID.class),
                rs.getString("task_slug"),
                rs.getObject("benchmark_version_id", UUID.class),
                rs.getString("benchmark_version"),
                rs.getBigDecimal("weighted_total"),
                rs.getString("failure_class"),
                toInstant(rs, "created_at"),
                toInstant(rs, "updated_at"),
                toInstant(rs, "completed_at")
        ));
    }

    public long countActiveEvaluations(UUID orgId) {
        Long count = jdbc.queryForObject("""
                        SELECT COUNT(*) FROM evaluations.evaluations
                        WHERE org_id = :orgId
                          AND state NOT IN ('COMPLETED', 'FAILED', 'CANCELLED', 'TIMED_OUT')
                        """,
                new MapSqlParameterSource("orgId", orgId),
                Long.class);
        return count == null ? 0L : count;
    }

    public long countAgents(UUID orgId) {
        Long count = jdbc.queryForObject("""
                        SELECT COUNT(*) FROM agents.agents WHERE org_id = :orgId AND enabled = TRUE
                        """,
                new MapSqlParameterSource("orgId", orgId),
                Long.class);
        return count == null ? 0L : count;
    }

    public long countBenchmarks(UUID orgId) {
        Long count = jdbc.queryForObject("""
                        SELECT COUNT(*) FROM benchmarks.benchmarks WHERE org_id = :orgId
                        """,
                new MapSqlParameterSource("orgId", orgId),
                Long.class);
        return count == null ? 0L : count;
    }

    private static String evaluationSearchFilters(
            String state,
            UUID agentId,
            UUID taskId,
            UUID benchmarkVersionId,
            String q,
            MapSqlParameterSource params) {
        StringBuilder sql = new StringBuilder();
        if (state != null && !state.isBlank()) {
            sql.append(" AND e.state = :state");
            params.addValue("state", state);
        }
        if (agentId != null) {
            sql.append(" AND e.agent_id = :agentId");
            params.addValue("agentId", agentId);
        }
        if (taskId != null) {
            sql.append(" AND e.task_id = :taskId");
            params.addValue("taskId", taskId);
        }
        if (benchmarkVersionId != null) {
            sql.append(" AND e.benchmark_version_id = :benchmarkVersionId");
            params.addValue("benchmarkVersionId", benchmarkVersionId);
        }
        if (q != null && !q.isBlank()) {
            sql.append("""
                     AND (
                        CAST(e.id AS TEXT) ILIKE :q
                        OR a.name ILIKE :q
                        OR t.slug ILIKE :q
                        OR t.title ILIKE :q
                        OR COALESCE(e.error_message, '') ILIKE :q
                     )
                    """);
            params.addValue("q", "%" + q.trim() + "%");
        }
        return sql.toString();
    }

    private static MapSqlParameterSource baseCostParams(UUID orgId, UUID agentId, Instant from, Instant to) {
        MapSqlParameterSource params = new MapSqlParameterSource("orgId", orgId);
        if (agentId != null) {
            params.addValue("agentId", agentId);
        }
        if (from != null) {
            params.addValue("from", Timestamp.from(from));
        }
        if (to != null) {
            params.addValue("to", Timestamp.from(to));
        }
        return params;
    }

    private static String costFilters(UUID agentId, Instant from, Instant to) {
        StringBuilder sql = new StringBuilder();
        if (agentId != null) {
            sql.append(" AND agent_id = :agentId");
        }
        if (from != null) {
            sql.append(" AND created_at >= :from");
        }
        if (to != null) {
            sql.append(" AND created_at <= :to");
        }
        return sql.toString();
    }

    private static String costJoinFilters(UUID agentId, Instant from, Instant to) {
        StringBuilder sql = new StringBuilder();
        if (agentId != null) {
            sql.append(" AND i.agent_id = :agentId");
        }
        if (from != null) {
            sql.append(" AND i.created_at >= :from");
        }
        if (to != null) {
            sql.append(" AND i.created_at <= :to");
        }
        return sql.toString();
    }

    private Map<String, Integer> readIntMap(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, Object> raw = objectMapper.readValue(json, new TypeReference<>() {
            });
            Map<String, Integer> result = new HashMap<>();
            for (Map.Entry<String, Object> entry : raw.entrySet()) {
                if (entry.getValue() instanceof Number number) {
                    result.put(entry.getKey(), number.intValue());
                }
            }
            return result;
        } catch (Exception ex) {
            return Map.of();
        }
    }

    private static Instant toInstant(ResultSet rs, String column) throws SQLException {
        Timestamp ts = rs.getTimestamp(column);
        return ts == null ? null : ts.toInstant();
    }

    private record RawComparison(
            UUID agentId,
            String agentName,
            String adapterType,
            UUID taskId,
            String taskSlug,
            UUID evaluationId,
            BigDecimal weightedTotal,
            Map<String, Integer> transitionSummary,
            Instant completedAt,
            UUID scoreId
    ) {
    }

    public record CostTotals(
            long invocationCount,
            long promptTokens,
            long completionTokens,
            long totalTokens,
            BigDecimal totalCost
    ) {
    }
}
