package com.agentbench.evaluation.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * Org-scoped quarantine entry for a known-flaky case and/or suite.
 * Suite-level entries use {@code caseKey == null}; case-level may omit suite.
 */
public record FlakeQuarantine(
        UUID id,
        UUID orgId,
        UUID suiteId,
        String caseKey,
        String reason,
        boolean active,
        UUID createdByUserId,
        Instant createdAt,
        Instant updatedAt,
        Instant expiresAt
) {
    public boolean isSuiteLevel() {
        return caseKey == null || caseKey.isBlank();
    }

    public boolean isExpired(Instant now) {
        return expiresAt != null && !expiresAt.isAfter(now);
    }

    public boolean isEffective(Instant now) {
        return active && !isExpired(now);
    }
}
