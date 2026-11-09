package com.agentbench.evaluation.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record QualityReport(
        UUID id,
        UUID evaluationId,
        UUID orgId,
        String tool,
        String metricsJson,
        BigDecimal normalizedScore,
        Instant createdAt
) {
}
