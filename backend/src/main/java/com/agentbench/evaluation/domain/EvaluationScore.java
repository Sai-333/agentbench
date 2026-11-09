package com.agentbench.evaluation.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record EvaluationScore(
        UUID id,
        UUID evaluationId,
        UUID orgId,
        BigDecimal weightedTotal,
        String profileId,
        String profileKey,
        ScoreStatus scoreStatus,
        String transitionSummaryJson,
        String rawSummaryJson,
        String warningsJson,
        Instant computedAt
) {
}
