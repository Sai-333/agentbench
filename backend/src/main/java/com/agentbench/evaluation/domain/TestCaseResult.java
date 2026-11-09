package com.agentbench.evaluation.domain;

import java.util.UUID;

public record TestCaseResult(
        UUID id,
        UUID testRunId,
        UUID orgId,
        String caseKey,
        TestCaseStatus status,
        int durationMs,
        String transition,
        String message,
        String logExcerptUri
) {
}
