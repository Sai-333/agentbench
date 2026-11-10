package com.agentbench.execution.domain;

/**
 * Isolation SPI — Docker (default), optional gVisor/runsc, optional Firecracker microVM,
 * local CI fallback (ADR-004). Evaluation code must not bind to Docker API types.
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
