package com.agentbench.evaluation.domain;

import java.time.Instant;
import java.util.UUID;

public record EvaluationTransition(
        long id,
        UUID evaluationId,
        UUID orgId,
        EvaluationState fromState,
        EvaluationState toState,
        String reason,
        String actor,
        int attempt,
        Instant createdAt
) {
}
