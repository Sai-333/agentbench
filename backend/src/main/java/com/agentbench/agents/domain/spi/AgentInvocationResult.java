package com.agentbench.agents.domain.spi;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Provider-independent invocation output. Never includes authoritative test results.
 */
public record AgentInvocationResult(
        AgentInvocationStatus status,
        String patchContent,
        String model,
        String providerVersion,
        Integer promptTokens,
        Integer completionTokens,
        Integer totalTokens,
        BigDecimal estimatedCostUsd,
        String errorCode,
        String errorMessage,
        Map<String, Object> metadata
) {
    public static AgentInvocationResult success(
            String patchContent,
            String model,
            String providerVersion,
            Integer promptTokens,
            Integer completionTokens,
            Integer totalTokens,
            BigDecimal estimatedCostUsd,
            Map<String, Object> metadata) {
        return new AgentInvocationResult(
                AgentInvocationStatus.SUCCEEDED,
                patchContent,
                model,
                providerVersion,
                promptTokens,
                completionTokens,
                totalTokens,
                estimatedCostUsd,
                null,
                null,
                metadata == null ? Map.of() : Map.copyOf(metadata)
        );
    }

    public static AgentInvocationResult failure(
            AgentInvocationStatus status,
            String errorCode,
            String errorMessage,
            String model,
            Map<String, Object> metadata) {
        return new AgentInvocationResult(
                status,
                null,
                model,
                null,
                null,
                null,
                null,
                null,
                errorCode,
                errorMessage,
                metadata == null ? Map.of() : Map.copyOf(metadata)
        );
    }
}
