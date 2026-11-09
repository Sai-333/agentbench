package com.agentbench.projects.domain;

import java.time.Instant;
import java.util.UUID;

public record Project(
        UUID id,
        UUID orgId,
        String name,
        String description,
        UUID createdBy,
        Instant createdAt,
        Instant updatedAt
) {
}
