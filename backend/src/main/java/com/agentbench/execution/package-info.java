/**
 * Execution: hardened {@link com.agentbench.execution.domain.RuntimeIsolator} (Docker-first),
 * workspace provisioning with separate agent vs hidden stages, patch validation,
 * fixture/JUnit suite runners, and Checkstyle/SpotBugs/PMD quality analysis.
 *
 * <p>Docker-only isolation is <strong>not</strong> production-safe for arbitrary untrusted
 * workloads (shared-kernel residual risk). gVisor/Firecracker planned (ADR-004).
 */
package com.agentbench.execution;
