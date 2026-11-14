package com.agentbench.evaluation.api.dto;

import com.agentbench.agents.api.dto.AgentDtos;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class EvaluationDtos {

    private EvaluationDtos() {
    }

    public record CreateEvaluationRequest(
            @NotNull UUID benchmarkVersionId,
            @NotNull UUID taskId,
            /** Omit to run the org's default Grok agent. */
            UUID agentId
    ) {
    }

    public record EvaluationResponse(
            UUID id,
            UUID orgId,
            UUID benchmarkVersionId,
            UUID taskId,
            UUID agentId,
            String state,
            int attempt,
            String failureClass,
            String errorCode,
            String errorMessage,
            boolean cancelRequested,
            UUID createdBy,
            Instant createdAt,
            Instant updatedAt,
            Instant startedAt,
            Instant completedAt
    ) {
    }

    public record TransitionResponse(
            long id,
            String fromState,
            String toState,
            String reason,
            String actor,
            int attempt,
            Instant createdAt
    ) {
    }

    public record TestCaseResultResponse(
            String caseKey,
            String status,
            int durationMs,
            String transition,
            String message,
            String category,
            boolean flaky,
            boolean quarantined,
            int attemptCount
    ) {
    }

    public record TestRunResponse(
            UUID id,
            String phase,
            String status,
            Instant startedAt,
            Instant finishedAt,
            List<TestCaseResultResponse> results
    ) {
    }

    public record PatchResponse(
            UUID id,
            String contentDigest,
            String format,
            int byteSize,
            Instant collectedAt,
            boolean sealed,
            Instant purgedAt
    ) {
    }

    public record ScoreMetricResponse(
            String metricKey,
            BigDecimal rawValue,
            BigDecimal weight,
            BigDecimal weightedValue,
            Map<String, Object> meta
    ) {
    }

    public record ScoreResponse(
            UUID id,
            String scoreStatus,
            BigDecimal weightedTotal,
            String profileKey,
            Map<String, Integer> transitionSummary,
            Map<String, Object> rawSummary,
            List<String> warnings,
            List<ScoreMetricResponse> metrics,
            Instant computedAt
    ) {
    }

    public record QualityFindingResponse(
            String ruleId,
            String severity,
            String filePath,
            int line,
            String message,
            boolean introduced
    ) {
    }

    public record QualityToolSummaryResponse(
            String tool,
            boolean stub,
            String notes,
            int totalFindings,
            int newFindings,
            int preExistingFindings,
            List<QualityFindingResponse> findingsSample
    ) {
    }

    public record QualityReportResponse(
            UUID id,
            String tool,
            BigDecimal normalizedScore,
            boolean stub,
            int newFindingCount,
            int preExistingFindingCount,
            List<QualityToolSummaryResponse> tools,
            Map<String, Object> metrics,
            Instant createdAt
    ) {
    }

    public record EvaluationDetailResponse(
            EvaluationResponse evaluation,
            List<TransitionResponse> transitions,
            List<TestRunResponse> testRuns,
            PatchResponse patch,
            List<AgentDtos.AgentInvocationResponse> agentInvocations,
            ScoreResponse score,
            List<QualityReportResponse> qualityReports
    ) {
    }

    public record EvaluationReportResponse(
            EvaluationResponse evaluation,
            ScoreResponse score,
            Map<String, Integer> visibleTransitions,
            HiddenSummary hidden,
            List<TestRunResponse> testRuns,
            List<QualityReportResponse> qualityReports
    ) {
    }

    public record HiddenSummary(
            int total,
            int passed,
            Double passRate,
            boolean neverLabeledF2P
    ) {
    }
}
