package com.agentbench.evaluation.domain;

/**
 * Whether a weighted total is meaningful. Invalid/incomplete/infra runs must not show misleading scores.
 */
public enum ScoreStatus {
    VALID,
    INVALID_BASELINE,
    INCOMPLETE,
    INFRA_FAILURE,
    SKIPPED
}
