package com.agentbench.agents.domain.spi;

/**
 * Provider-independent invocation outcome status.
 */
public enum AgentInvocationStatus {
    SUCCEEDED,
    FAILED,
    TIMED_OUT,
    CANCELLED,
    NOT_IMPLEMENTED
}
