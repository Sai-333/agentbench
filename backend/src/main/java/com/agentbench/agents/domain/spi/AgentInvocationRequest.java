package com.agentbench.agents.domain.spi;

import com.agentbench.agents.domain.Agent;

import java.time.Duration;
import java.util.UUID;

/**
 * Provider-independent invocation input. Evaluation engine depends only on this SPI.
 * API keys are resolved by orchestration and must never be logged or persisted in clear text.
 */
public record AgentInvocationRequest(
        UUID correlationId,
        UUID orgId,
        UUID evaluationId,
        UUID taskId,
        Agent agent,
        String taskPrompt,
        String visibleContextJson,
        String referencePatchHint,
        /** Decrypted credential for this call only; null for FAKE / no-auth adapters. */
        String apiKey,
        Duration timeout,
        int attempt
) {
}
