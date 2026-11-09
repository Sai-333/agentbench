package com.agentbench.benchmarks.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record Task(
        UUID id,
        UUID benchmarkVersionId,
        UUID orgId,
        String slug,
        String title,
        String prompt,
        String acceptanceCriteria,
        String category,
        TaskDifficulty difficulty,
        List<String> languageTags,
        UUID repoSnapshotId,
        String baselineCommitSha,
        String referencePatch,
        String expectedBehavior,
        int timeoutSec,
        int memoryMb,
        BigDecimal cpuLimit,
        int maxPatchBytes,
        String evalConfigJson,
        int ordinal,
        Instant createdAt,
        Instant updatedAt
) {
}
