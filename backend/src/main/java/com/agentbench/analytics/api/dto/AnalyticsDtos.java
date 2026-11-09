package com.agentbench.analytics.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class AnalyticsDtos {

    private AnalyticsDtos() {
    }

    public record PageMeta(int page, int size, long total) {
    }

    public record OverviewResponse(
            long totalEvaluations,
            long completed,
            long failed,
            long cancelled,
            long timedOut,
            long inFlight,
            long queued,
            Double averageWeightedTotal,
            BigDecimal totalEstimatedCostUsd,
            Long totalTokens,
            Map<String, Long> countsByState,
            List<RecentEvaluationSummary> recent,
            List<DailyVolumePoint> volumeByDay
    ) {
    }

    public record RecentEvaluationSummary(
            UUID id,
            String state,
            UUID agentId,
            String agentName,
            UUID taskId,
            String taskSlug,
            UUID benchmarkVersionId,
            BigDecimal weightedTotal,
            Instant createdAt,
            Instant completedAt
    ) {
    }

    public record DailyVolumePoint(String day, long started, long completed) {
    }

    public record LeaderboardResponse(
            UUID benchmarkVersionId,
            String scoringProfileKey,
            String note,
            List<LeaderboardEntry> entries,
            PageMeta page
    ) {
    }

    public record LeaderboardEntry(
            int rank,
            UUID agentId,
            String agentName,
            String adapterType,
            UUID taskId,
            String taskSlug,
            UUID evaluationId,
            BigDecimal weightedTotal,
            String scoreStatus,
            Map<String, Integer> transitionSummary,
            Instant completedAt,
            Long durationMs,
            Long totalTokens,
            BigDecimal estimatedCostUsd
    ) {
    }

    public record ComparisonResponse(
            UUID benchmarkVersionId,
            String scoringProfileKey,
            List<UUID> agentIds,
            List<ComparisonRow> rows,
            List<String> metricKeys
    ) {
    }

    public record ComparisonRow(
            UUID agentId,
            String agentName,
            String adapterType,
            UUID taskId,
            String taskSlug,
            UUID evaluationId,
            BigDecimal weightedTotal,
            Map<String, BigDecimal> metricRaws,
            Map<String, Integer> transitionSummary,
            Instant completedAt
    ) {
    }

    public record CostAnalyticsResponse(
            BigDecimal totalEstimatedCostUsd,
            long totalPromptTokens,
            long totalCompletionTokens,
            long totalTokens,
            long invocationCount,
            List<CostByAgent> byAgent,
            List<CostByDay> byDay,
            PageMeta page
    ) {
    }

    public record CostByAgent(
            UUID agentId,
            String agentName,
            String adapterType,
            long invocationCount,
            long totalTokens,
            BigDecimal estimatedCostUsd
    ) {
    }

    public record CostByDay(
            String day,
            long invocationCount,
            long totalTokens,
            BigDecimal estimatedCostUsd
    ) {
    }

    public record EvaluationSearchResponse(
            List<EvaluationSearchItem> items,
            PageMeta page
    ) {
    }

    public record EvaluationSearchItem(
            UUID id,
            String state,
            UUID agentId,
            String agentName,
            UUID taskId,
            String taskSlug,
            UUID benchmarkVersionId,
            String benchmarkVersion,
            BigDecimal weightedTotal,
            String failureClass,
            Instant createdAt,
            Instant updatedAt,
            Instant completedAt
    ) {
    }

    public record SystemHealthResponse(
            String status,
            Instant checkedAt,
            Map<String, ComponentHealth> components,
            Map<String, Object> counts
    ) {
    }

    public record ComponentHealth(
            String status,
            String detail
    ) {
    }
}
