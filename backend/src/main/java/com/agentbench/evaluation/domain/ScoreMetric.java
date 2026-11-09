package com.agentbench.evaluation.domain;

import java.math.BigDecimal;
import java.util.UUID;

public record ScoreMetric(
        UUID id,
        UUID evaluationScoreId,
        String metricKey,
        BigDecimal rawValue,
        BigDecimal weight,
        BigDecimal weightedValue,
        String metaJson
) {
}
