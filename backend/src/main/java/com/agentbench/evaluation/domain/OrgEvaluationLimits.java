package com.agentbench.evaluation.domain;

import java.util.UUID;

public record OrgEvaluationLimits(
        UUID orgId,
        int maxConcurrentEvaluations,
        int maxQueuedEvaluations,
        int maxConcurrentAgentCalls,
        int maxDurationSec,
        int maxPatchBytes,
        int maxLogArtifactBytes
) {
    public static OrgEvaluationLimits defaults(UUID orgId) {
        return new OrgEvaluationLimits(orgId, 2, 20, 2, 1800, 5_242_880, 20_971_520);
    }
}
