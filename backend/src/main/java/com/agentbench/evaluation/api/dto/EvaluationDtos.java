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
            @NotNull UUID agentId
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
            boolean sealed
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

    public record EvaluationDetailResponse(
            EvaluationResponse evaluation,
            List<TransitionResponse> transitions,
            List<TestRunResponse> testRuns,
            PatchResponse patch,
            List<AgentDtos.AgentInvocationResponse> agentInvocations,
            ScoreResponse score
    ) {
    }

    public record EvaluationReportResponse(
            EvaluationResponse evaluation,
            ScoreResponse score,
            Map<String, Integer> visibleTransitions,
            HiddenSummary hidden,
            List<TestRunResponse> testRuns
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
