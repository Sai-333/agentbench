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
        boolean sealed
) {
}
