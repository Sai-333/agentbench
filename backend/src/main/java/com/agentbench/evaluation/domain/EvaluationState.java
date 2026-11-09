package com.agentbench.evaluation.domain;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Persistent evaluation lifecycle states from PRODUCT_REQUIREMENTS.md §6 /
 * EVALUATION_ENGINE.md.
 */
public enum EvaluationState {
    QUEUED,
    PREPARING_REPO,
    RUNNING_BASELINE,
    INVOKING_AGENT,
    COLLECTING_PATCH,
    VALIDATING_PATCH,
    APPLYING_PATCH,
    RUNNING_VISIBLE_TESTS,
    RUNNING_HIDDEN_TESTS,
    ANALYZING_QUALITY,
    SCORING,
    COMPLETED,
    FAILED,
    CANCELLED,
    TIMED_OUT;

    private static final Set<EvaluationState> TERMINAL = EnumSet.of(
            COMPLETED, FAILED, CANCELLED, TIMED_OUT);

    private static final Set<EvaluationState> ACTIVE = EnumSet.of(
            PREPARING_REPO,
            RUNNING_BASELINE,
            INVOKING_AGENT,
            COLLECTING_PATCH,
            VALIDATING_PATCH,
            APPLYING_PATCH,
            RUNNING_VISIBLE_TESTS,
            RUNNING_HIDDEN_TESTS,
            ANALYZING_QUALITY,
            SCORING);

    private static final Set<EvaluationState> CANCELABLE = EnumSet.of(
            QUEUED,
            PREPARING_REPO,
            RUNNING_BASELINE,
            INVOKING_AGENT,
            COLLECTING_PATCH,
            VALIDATING_PATCH,
            APPLYING_PATCH,
            RUNNING_VISIBLE_TESTS,
            RUNNING_HIDDEN_TESTS,
            ANALYZING_QUALITY,
            SCORING);

    private static final Map<EvaluationState, Set<EvaluationState>> ALLOWED = Map.ofEntries(
            Map.entry(QUEUED, Set.of(PREPARING_REPO, CANCELLED, TIMED_OUT, FAILED)),
            Map.entry(PREPARING_REPO, Set.of(RUNNING_BASELINE, FAILED, CANCELLED, TIMED_OUT)),
            Map.entry(RUNNING_BASELINE, Set.of(INVOKING_AGENT, FAILED, CANCELLED, TIMED_OUT)),
            Map.entry(INVOKING_AGENT, Set.of(COLLECTING_PATCH, FAILED, CANCELLED, TIMED_OUT)),
            Map.entry(COLLECTING_PATCH, Set.of(VALIDATING_PATCH, FAILED, CANCELLED, TIMED_OUT)),
            Map.entry(VALIDATING_PATCH, Set.of(APPLYING_PATCH, FAILED, CANCELLED, TIMED_OUT)),
            Map.entry(APPLYING_PATCH, Set.of(RUNNING_VISIBLE_TESTS, FAILED, CANCELLED, TIMED_OUT)),
            Map.entry(RUNNING_VISIBLE_TESTS, Set.of(RUNNING_HIDDEN_TESTS, FAILED, CANCELLED, TIMED_OUT)),
            Map.entry(RUNNING_HIDDEN_TESTS, Set.of(ANALYZING_QUALITY, FAILED, CANCELLED, TIMED_OUT)),
            Map.entry(ANALYZING_QUALITY, Set.of(SCORING, FAILED, CANCELLED, TIMED_OUT)),
            Map.entry(SCORING, Set.of(COMPLETED, FAILED, CANCELLED, TIMED_OUT)),
            Map.entry(COMPLETED, Set.of()),
            Map.entry(FAILED, Set.of()),
            Map.entry(CANCELLED, Set.of()),
            Map.entry(TIMED_OUT, Set.of())
    );

    public boolean isTerminal() {
        return TERMINAL.contains(this);
    }

    public boolean isActive() {
        return ACTIVE.contains(this) || this == QUEUED;
    }

    public boolean isCancelable() {
        return CANCELABLE.contains(this);
    }

    public boolean canTransitionTo(EvaluationState target) {
        return ALLOWED.getOrDefault(this, Set.of()).contains(target);
    }

    public Set<EvaluationState> allowedTargets() {
        return Collections.unmodifiableSet(ALLOWED.getOrDefault(this, Set.of()));
    }

    public static EvaluationState fromString(String value) {
        try {
            return EvaluationState.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (Exception ex) {
            throw new IllegalArgumentException("Unknown evaluation state: " + value);
        }
    }
}
