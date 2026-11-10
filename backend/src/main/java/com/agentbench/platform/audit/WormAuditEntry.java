package com.agentbench.platform.audit;

import java.time.Instant;
import java.util.UUID;

/**
 * One append-only hash-chained row from {@code platform.worm_audit_events}.
 */
public record WormAuditEntry(
        long id,
        UUID orgId,
        String eventType,
        Instant eventTime,
        String actor,
        String resourceType,
        String resourceId,
        String payloadJson,
        String prevHash,
        String entryHash,
        Instant createdAt
) {
}
