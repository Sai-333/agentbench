package com.agentbench.evaluation.api.dto;

import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.UUID;

public final class FlakeQuarantineDtos {

    private FlakeQuarantineDtos() {
    }

    public record CreateRequest(
            UUID suiteId,
            @Size(max = 512) String caseKey,
            @Size(max = 1000) String reason,
            String expiresAt
    ) {
    }

    public record Response(
            UUID id,
            UUID orgId,
            UUID suiteId,
            String caseKey,
            String scope,
            String reason,
            boolean active,
            UUID createdByUserId,
            Instant createdAt,
            Instant updatedAt,
            Instant expiresAt
    ) {
    }
}
