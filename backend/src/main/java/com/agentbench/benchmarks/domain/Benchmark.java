package com.agentbench.benchmarks.domain;

import java.time.Instant;
import java.util.UUID;

public record Benchmark(
        UUID id,
        UUID orgId,
        UUID projectId,
        String name,
        String description,
        UUID createdBy,
        Instant createdAt,
        Instant updatedAt
) {
}
