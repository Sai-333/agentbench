package com.agentbench.evaluation.domain;

import java.time.Instant;
import java.util.UUID;

public record PatchArtifact(
        UUID id,
        UUID evaluationId,
        UUID orgId,
        String contentDigest,
        String storageUri,
        String format,
        int byteSize,
        Instant collectedAt,
        boolean sealed,
        Instant purgedAt
) {
    /** Convenience for newly collected patches (not yet purged). */
    public PatchArtifact(
            UUID id,
            UUID evaluationId,
            UUID orgId,
            String contentDigest,
            String storageUri,
            String format,
            int byteSize,
            Instant collectedAt,
            boolean sealed) {
        this(id, evaluationId, orgId, contentDigest, storageUri, format, byteSize, collectedAt, sealed, null);
    }

    public boolean isPurged() {
        return purgedAt != null;
    }
}
