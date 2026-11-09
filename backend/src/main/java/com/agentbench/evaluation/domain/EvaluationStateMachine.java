package com.agentbench.evaluation.domain;

import com.agentbench.platform.error.ApiException;

/**
 * Validates evaluation state transitions. Invalid transitions are rejected.
 */
public final class EvaluationStateMachine {

    private EvaluationStateMachine() {
    }

    public static void assertTransition(EvaluationState from, EvaluationState to) {
        if (from == null) {
            if (to != EvaluationState.QUEUED) {
                throw ApiException.conflict("Initial evaluation state must be QUEUED");
            }
            return;
        }
        if (!from.canTransitionTo(to)) {
            throw ApiException.conflict("Illegal evaluation transition: " + from + " → " + to);
        }
    }

    public static EvaluationState nextHappyPath(EvaluationState current) {
        return switch (current) {
            case QUEUED -> EvaluationState.PREPARING_REPO;
            case PREPARING_REPO -> EvaluationState.RUNNING_BASELINE;
            case RUNNING_BASELINE -> EvaluationState.INVOKING_AGENT;
            case INVOKING_AGENT -> EvaluationState.COLLECTING_PATCH;
            case COLLECTING_PATCH -> EvaluationState.VALIDATING_PATCH;
            case VALIDATING_PATCH -> EvaluationState.APPLYING_PATCH;
            case APPLYING_PATCH -> EvaluationState.RUNNING_VISIBLE_TESTS;
            case RUNNING_VISIBLE_TESTS -> EvaluationState.RUNNING_HIDDEN_TESTS;
            case RUNNING_HIDDEN_TESTS -> EvaluationState.ANALYZING_QUALITY;
            case ANALYZING_QUALITY -> EvaluationState.SCORING;
            case SCORING -> EvaluationState.COMPLETED;
            default -> throw ApiException.conflict("No happy-path successor for " + current);
        };
    }
}
