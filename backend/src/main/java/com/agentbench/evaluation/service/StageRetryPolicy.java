package com.agentbench.evaluation.service;

import com.agentbench.evaluation.domain.FailureClass;
import com.agentbench.evaluation.domain.FailureCode;

/**
 * Decides whether a failed evaluation stage should be re-queued.
 * Infrastructure failures retry up to {@code maxAttempts}; baseline/agent/user/internal do not.
 */
public final class StageRetryPolicy {

    private StageRetryPolicy() {
    }

    public static boolean shouldRetry(FailureCode code, int attempt, int maxAttempts) {
        if (code == null || maxAttempts < 1) {
            return false;
        }
        return attempt < maxAttempts && code.failureClass() == FailureClass.INFRASTRUCTURE;
    }
}
