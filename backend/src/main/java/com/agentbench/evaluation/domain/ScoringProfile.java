package com.agentbench.evaluation.domain;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record ScoringProfile(
        UUID id,
        String profileKey,
        String displayName,
        String description,
        Map<String, Double> weights,
        int flakyRepeatRuns,
        boolean active,
        Instant createdAt
) {
    public static final String DEFAULT_KEY = "default-v1";

    public static final Map<String, Double> DEFAULT_WEIGHTS = Map.of(
            MetricKeys.TARGET_FAILURE_RESOLUTION, 0.30,
            MetricKeys.REGRESSION, 0.25,
            MetricKeys.HIDDEN, 0.20,
            MetricKeys.CODE_QUALITY, 0.10,
            MetricKeys.EDGE_CASE, 0.10,
            MetricKeys.EFFICIENCY, 0.05
    );

    public static final class MetricKeys {
        public static final String TARGET_FAILURE_RESOLUTION = "target_failure_resolution";
        public static final String REGRESSION = "regression";
        public static final String HIDDEN = "hidden";
        public static final String CODE_QUALITY = "code_quality";
        public static final String EDGE_CASE = "edge_case";
        public static final String EFFICIENCY = "efficiency";

        private MetricKeys() {
        }
    }
}
