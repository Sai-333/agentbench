package com.agentbench.agents.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public final class AgentDtos {

    private AgentDtos() {
    }

    public record CreateAgentRequest(
            @NotBlank @Size(max = 120) String name,
            @NotBlank String adapterType,
            UUID secretRefId,
            Map<String, Object> config
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
}
