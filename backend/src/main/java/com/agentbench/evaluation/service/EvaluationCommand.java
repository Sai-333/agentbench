package com.agentbench.evaluation.service;

import com.agentbench.evaluation.domain.EvaluationState;

import java.util.UUID;

/**
 * RabbitMQ / outbox command payload for stage execution.
 */
public record EvaluationCommand(
        String messageId,
        UUID evaluationId,
        UUID orgId,
        EvaluationState targetState,
        int attempt
) {
}
