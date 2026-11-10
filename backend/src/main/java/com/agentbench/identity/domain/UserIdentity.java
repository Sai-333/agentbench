package com.agentbench.identity.domain;

import java.time.Instant;
import java.util.UUID;

public record UserIdentity(
        UUID id,
        UUID userId,
        String provider,
        String subject,
        String email,
        Instant createdAt,
        Instant updatedAt
) {
}
