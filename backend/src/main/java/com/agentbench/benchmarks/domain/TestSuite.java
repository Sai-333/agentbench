package com.agentbench.benchmarks.domain;

import java.time.Instant;
import java.util.UUID;

public record TestSuite(
        UUID id,
        UUID taskId,
        UUID orgId,
        SuiteVisibility visibility,
        String framework,
        String storageUri,
        String entryCommand,
        String contentDigest,
        String fixtureContent,
        String expectedOutcomesJson,
        Integer caseCount,
        Instant createdAt,
        Instant updatedAt
) {
}
