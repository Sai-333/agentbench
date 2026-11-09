package com.agentbench.agents.domain;

import java.time.Instant;
import java.util.UUID;

public record Agent(
        UUID id,
        UUID orgId,
        String name,
        String adapterType,
        UUID secretRefId,
        String configJson,
        boolean enabled,
        UUID createdBy,
        Instant createdAt,
        Instant updatedAt
) {
}
