package com.agentbench.evaluation.api.dto;

import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.List;
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
            String message
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

    public record EvaluationDetailResponse(
            EvaluationResponse evaluation,
            List<TransitionResponse> transitions,
            List<TestRunResponse> testRuns,
            PatchResponse patch
    ) {
    }
}
