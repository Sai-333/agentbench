package com.agentbench.evaluation.domain;

/**
 * Engine-level failure codes (EVALUATION_ENGINE.md §8).
 */
public enum FailureCode {
    BASELINE_NOT_REPRODUCIBLE(FailureClass.BASELINE),
    AGENT_TIMEOUT(FailureClass.AGENT),
    AGENT_ERROR(FailureClass.AGENT),
    PATCH_INVALID(FailureClass.AGENT),
    PATCH_APPLY_CONFLICT(FailureClass.AGENT),
    RUNNER_ERROR(FailureClass.INFRASTRUCTURE),
    WORKSPACE_ERROR(FailureClass.INFRASTRUCTURE),
    QUEUE_ERROR(FailureClass.INFRASTRUCTURE),
    CANCELLED_BY_USER(FailureClass.USER),
    TIMED_OUT(FailureClass.INFRASTRUCTURE),
    CONCURRENCY_LIMIT(FailureClass.USER),
    INTERNAL(FailureClass.INTERNAL);

    private final FailureClass failureClass;

    FailureCode(FailureClass failureClass) {
        this.failureClass = failureClass;
    }

    public FailureClass failureClass() {
        return failureClass;
    }
}
