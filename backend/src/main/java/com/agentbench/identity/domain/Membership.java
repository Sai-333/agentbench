package com.agentbench.identity.domain;

import java.time.Instant;
import java.util.UUID;

public record Membership(
        UUID orgId,
        UUID userId,
        OrgRole role,
        Instant createdAt,
        Instant updatedAt
) {
}
