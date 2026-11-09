package com.agentbench.benchmarks.domain;

import java.util.Locale;

public enum BenchmarkStatus {
    DRAFT,
    PUBLISHED,
    RETIRED;

    public static BenchmarkStatus fromString(String value) {
        try {
            return BenchmarkStatus.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (Exception ex) {
            throw new IllegalArgumentException("Unknown benchmark status: " + value);
        }
    }

    public boolean isImmutable() {
        return this == PUBLISHED || this == RETIRED;
    }
}
