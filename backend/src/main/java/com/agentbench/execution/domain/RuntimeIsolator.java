package com.agentbench.execution.domain;

/**
 * Isolation SPI — Docker (default), optional gVisor/runsc, local CI fallback (ADR-004).
 * Evaluation code must not bind to Docker API types. Firecracker is not implemented.
 */
public interface RuntimeIsolator {

    String runtimeName();

    /**
     * Whether this isolator can currently execute containerized workloads.
     * Local/CI fallback returns false for Docker when the daemon is unavailable.
     */
    boolean available();

    IsolationResult run(IsolationSpec spec);

    /**
     * Best-effort cleanup of ephemeral containers / temp mounts for a run name prefix.
     */
    void cleanup(String runNamePrefix);
}
