package com.agentbench.agents.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public final class AgentDtos {

    private AgentDtos() {
    }

    public record CreateAgentRequest(
            @NotBlank @Size(max = 120) String name,
            /** Omit to register the default Grok provider. */
            String adapterType,
            UUID secretRefId,
            Map<String, Object> config
    ) {
    }

    public record UpdateAgentRequest(
            @Size(max = 120) String name,
            String adapterType,
            UUID secretRefId,
            Boolean clearSecretRef,
            Map<String, Object> config,
            Boolean enabled
    ) {
    }

    public record AgentResponse(
            UUID id,
            UUID orgId,
            String name,
            String adapterType,
            UUID secretRefId,
            Map<String, Object> config,
            boolean enabled,
            UUID createdBy,
            Instant createdAt,
            Instant updatedAt
    ) {
    }

    public record TestConnectionResponse(
            boolean ok,
            String provider,
            String detail,
            Boolean hasCredentials
    ) {
    }

    public record AgentInvocationResponse(
            UUID id,
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
            Integer promptTokens,
            Integer completionTokens,
            Integer totalTokens,
            BigDecimal estimatedCostUsd,
            String errorCode,
            String errorMessage,
            Map<String, Object> meta,
            Instant createdAt
    ) {
    }
}
