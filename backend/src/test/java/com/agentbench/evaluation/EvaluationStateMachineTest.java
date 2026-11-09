package com.agentbench.evaluation;

import com.agentbench.evaluation.domain.EvaluationState;
import com.agentbench.evaluation.domain.EvaluationStateMachine;
import com.agentbench.platform.error.ApiException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EvaluationStateMachineTest {

    @Test
    void happyPathTransitionsAreValid() {
        EvaluationState state = EvaluationState.QUEUED;
        while (!state.isTerminal()) {
            EvaluationState from = state;
            EvaluationState next = EvaluationStateMachine.nextHappyPath(from);
            assertDoesNotThrow(() -> EvaluationStateMachine.assertTransition(from, next));
            state = next;
        }
        assertEquals(EvaluationState.COMPLETED, state);
    }

    @Test
    void rejectsIllegalTransitions() {
        assertThrows(ApiException.class,
                () -> EvaluationStateMachine.assertTransition(EvaluationState.QUEUED, EvaluationState.SCORING));
        assertThrows(ApiException.class,
                () -> EvaluationStateMachine.assertTransition(EvaluationState.COMPLETED, EvaluationState.QUEUED));
    }

    @Test
    void cancelAndTimeoutFromQueued() {
        assertTrue(EvaluationState.QUEUED.canTransitionTo(EvaluationState.CANCELLED));
        assertTrue(EvaluationState.QUEUED.canTransitionTo(EvaluationState.TIMED_OUT));
        assertTrue(EvaluationState.RUNNING_BASELINE.isCancelable());
        assertFalse(EvaluationState.COMPLETED.isCancelable());
    }
}
