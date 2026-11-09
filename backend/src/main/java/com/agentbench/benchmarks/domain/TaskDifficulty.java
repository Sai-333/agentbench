package com.agentbench.benchmarks.domain;

import java.util.Locale;

public enum TaskDifficulty {
    EASY,
    MEDIUM,
    HARD,
    EXPERT;

    public static TaskDifficulty fromString(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return TaskDifficulty.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (Exception ex) {
            throw new IllegalArgumentException("Unknown difficulty: " + value);
        }
    }
}
