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
        String logExcerptUri,
        CaseCategory category,
        boolean flaky,
        int attemptCount,
        String metaJson
) {
    public TestCaseResult(
            UUID id,
            UUID testRunId,
            UUID orgId,
            String caseKey,
            TestCaseStatus status,
            int durationMs,
            String transition,
            String message,
            String logExcerptUri) {
        this(id, testRunId, orgId, caseKey, status, durationMs, transition, message, logExcerptUri,
                null, false, 1, "{}");
    }
}
