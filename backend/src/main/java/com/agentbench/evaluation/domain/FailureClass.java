package com.agentbench.evaluation.domain;

import java.util.Locale;

/**
 * High-level failure taxonomy separating infrastructure vs agent failures.
 */
public enum FailureClass {
    /** Runner/container/queue/storage failures. */
    INFRASTRUCTURE,
    /** Adapter/timeout/malformed agent output. */
    AGENT,
    /** User-initiated cancel. */
    USER,
    /** Baseline could not reproduce the expected bug. */
    BASELINE,
    /** Unexpected internal errors. */
    INTERNAL;

    public static FailureClass fromString(String value) {
        try {
            return FailureClass.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (Exception ex) {
            throw new IllegalArgumentException("Unknown failure class: " + value);
        }
    }
}
