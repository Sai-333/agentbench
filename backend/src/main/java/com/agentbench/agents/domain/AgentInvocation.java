package com.agentbench.agents.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Persisted audit row for one provider attempt.
 */
public record AgentInvocation(
        UUID id,
        UUID orgId,
        UUID evaluationId,
        UUID agentId,
        UUID taskId,
        String provider,
        String model,
        String providerVersion,
        String prompt,
        String status,
        int attempt,
        int maxAttempts,
        Instant startedAt,
        Instant finishedAt,
        String patchDigest,
        Integer patchByteSize,
        String patchStorageUri,
        Integer promptTokens,
        Integer completionTokens,
        Integer totalTokens,
        BigDecimal estimatedCostUsd,
        String errorCode,
        String errorMessage,
        String metaJson,
        Instant createdAt
) {
}
