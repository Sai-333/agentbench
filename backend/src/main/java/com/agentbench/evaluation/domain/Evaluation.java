package com.agentbench.evaluation.domain;

import java.time.Instant;
import java.util.UUID;

public record Evaluation(
        UUID id,
        UUID orgId,
        UUID benchmarkVersionId,
        UUID taskId,
        UUID agentId,
        EvaluationState state,
        int attempt,
        FailureClass failureClass,
        String errorCode,
        String errorMessage,
        String idempotencyKey,
        String snapshotJson,
        String workspaceUri,
        boolean cancelRequested,
        UUID createdBy,
        Instant createdAt,
        Instant updatedAt,
        Instant startedAt,
        Instant completedAt
) {
}
