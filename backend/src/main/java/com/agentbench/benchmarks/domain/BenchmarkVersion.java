package com.agentbench.benchmarks.domain;

import java.time.Instant;
import java.util.UUID;

public record BenchmarkVersion(
        UUID id,
        UUID benchmarkId,
        UUID orgId,
        String version,
        BenchmarkStatus status,
        String changelog,
        String scoringProfileKey,
        Instant publishedAt,
        UUID publishedBy,
        String immutableSnapshotJson,
        Instant createdAt,
        Instant updatedAt
) {
}
