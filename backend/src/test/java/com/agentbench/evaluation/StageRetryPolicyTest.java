package com.agentbench.evaluation;

import com.agentbench.evaluation.domain.FailureCode;
import com.agentbench.evaluation.service.StageRetryPolicy;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StageRetryPolicyTest {

    @Test
    void retriesInfrastructureUntilMaxAttemptsExhausted() {
        assertTrue(StageRetryPolicy.shouldRetry(FailureCode.RUNNER_ERROR, 1, 5));
        assertTrue(StageRetryPolicy.shouldRetry(FailureCode.WORKSPACE_ERROR, 4, 5));
        assertFalse(StageRetryPolicy.shouldRetry(FailureCode.QUEUE_ERROR, 5, 5));
        assertFalse(StageRetryPolicy.shouldRetry(FailureCode.TIMED_OUT, 5, 5));
    }

    @Test
    void neverRetriesBaselineAgentUserOrInternal() {
        assertFalse(StageRetryPolicy.shouldRetry(FailureCode.BASELINE_NOT_REPRODUCIBLE, 1, 5));
        assertFalse(StageRetryPolicy.shouldRetry(FailureCode.AGENT_ERROR, 1, 5));
        assertFalse(StageRetryPolicy.shouldRetry(FailureCode.PATCH_INVALID, 1, 5));
        assertFalse(StageRetryPolicy.shouldRetry(FailureCode.CANCELLED_BY_USER, 1, 5));
        assertFalse(StageRetryPolicy.shouldRetry(FailureCode.INTERNAL, 1, 5));
    }
}
