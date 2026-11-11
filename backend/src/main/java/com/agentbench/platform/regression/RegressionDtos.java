package com.agentbench.platform.regression;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Platform-scoped regression run payloads. {@code scope} is always {@code platform}.
 * Counts are the stored case totals, not a client-supplied summary.
 */
public final class RegressionDtos {

    public static final String SCOPE = "platform";

    private RegressionDtos() {
    }

    public record CaseResponse(String name, String status, long durationMs, String message) {
    }

    public record RunSummary(
            UUID id,
            String gitRepo,
            String gitBranch,
            String gitSha,
            String suiteName,
            Instant startedAt,
            Instant finishedAt,
            int passed,
            int failed,
            int skipped,
            int caseCount,
            String scope,
            UUID ingestedBy
    ) {
    }

    public record RunDetail(
            UUID id,
            String gitRepo,
            String gitBranch,
            String gitSha,
            String suiteName,
            Instant startedAt,
            Instant finishedAt,
            int passed,
            int failed,
            int skipped,
            int caseCount,
            String scope,
            UUID ingestedBy,
            List<CaseResponse> cases
    ) {
    }
}
