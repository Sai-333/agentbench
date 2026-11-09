package com.agentbench.benchmarks.domain;

import java.util.Locale;

public enum SuiteVisibility {
    VISIBLE,
    HIDDEN;

    public static SuiteVisibility fromString(String value) {
        try {
            return SuiteVisibility.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (Exception ex) {
            throw new IllegalArgumentException("Unknown suite visibility: " + value);
        }
    }
}
