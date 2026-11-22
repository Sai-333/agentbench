package com.agentbench.analytics.infra;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task and version used to be concatenated onto ORDER BY, so JDBC looked up a
 * parameter that was never bound. The API then surfaced that as authentication required.
 */
class EvaluationSearchSqlTest {

    @Test
    void taskAndVersionStaySeparateFromOrderBy() {
        UUID taskId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        MapSqlParameterSource params = baseParams();
        String sql = AnalyticsRepository.searchEvaluationsSql(
                null, null, taskId, versionId, null, null, null, null, null, null, params);

        assertTrue(sql.contains("\nORDER BY e.created_at DESC\n"));
        assertFalse(sql.contains("taskIdORDER"));
        assertFalse(sql.contains("benchmarkVersionIdORDER"));
        assertFalse(sql.contains("IdORDER"));
        NamedParameterUtils.buildValueArray(sql, params.getValues());
    }

    @Test
    void stateAgentAndSearchBindWithoutSwallowingOrderBy() {
        MapSqlParameterSource params = baseParams();
        String sql = AnalyticsRepository.searchEvaluationsSql(
                "FAILED",
                UUID.randomUUID(),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                "fixer",
                params);
        assertFalse(sql.contains("stateORDER"));
        assertFalse(sql.contains("agentIdORDER"));
        NamedParameterUtils.buildValueArray(sql, params.getValues());
    }

    @Test
    void failureScoreAndCreatedTimeFiltersBindAndExcludeUnscoredRows() {
        MapSqlParameterSource params = baseParams();
        String sql = AnalyticsRepository.searchEvaluationsSql(
                null,
                null,
                null,
                null,
                "AGENT",
                new BigDecimal("10"),
                new BigDecimal("90"),
                Instant.parse("2026-11-01T00:00:00Z"),
                Instant.parse("2026-11-22T00:00:00Z"),
                null,
                params);
        assertTrue(sql.contains("e.failure_class = :failureClass"));
        assertTrue(sql.contains("s.weighted_total IS NOT NULL"));
        assertTrue(sql.contains("s.weighted_total >= :minScore"));
        assertTrue(sql.contains("s.weighted_total <= :maxScore"));
        assertTrue(sql.contains("e.created_at >= :createdFrom"));
        assertTrue(sql.contains("e.created_at <= :createdTo"));
        NamedParameterUtils.buildValueArray(sql, params.getValues());

        MapSqlParameterSource noneParams = baseParams();
        String none = AnalyticsRepository.countEvaluationsSql(
                null, null, null, null, AnalyticsRepository.NO_FAILURE_CLASS,
                null, null, null, null, null, noneParams);
        assertTrue(none.contains("e.failure_class IS NULL"));
        assertTrue(none.contains("evaluations.evaluation_scores"));
        assertFalse(none.contains(":failureClass"));
        NamedParameterUtils.buildValueArray(none, noneParams.getValues());
    }

    private static MapSqlParameterSource baseParams() {
        return new MapSqlParameterSource("orgId", UUID.randomUUID())
                .addValue("limit", 20)
                .addValue("offset", 0);
    }
}
