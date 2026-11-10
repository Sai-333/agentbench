/**
 * Execution: hardened {@link com.agentbench.execution.domain.RuntimeIsolator} (Docker default,
 * optional gVisor/runsc, local CI fallback), workspace provisioning with separate agent vs
 * hidden stages, patch validation, fixture/JUnit suite runners, and Checkstyle/SpotBugs/PMD
 * quality analysis.
 *
 * <p>Docker-only isolation is <strong>not</strong> production-safe for arbitrary untrusted
 * workloads (shared-kernel residual risk). Opt into gVisor via
 * {@code agentbench.execution.mode=gvisor} on Linux hosts with runsc (ADR-004). Firecracker
 * is not implemented.
 */
package com.agentbench.execution;
