/**
 * Execution: hardened {@link com.agentbench.execution.domain.RuntimeIsolator} (Docker default,
 * optional gVisor/runsc, optional Firecracker microVM, local CI fallback), workspace
 * provisioning with separate agent vs hidden stages, patch validation, fixture/JUnit suite
 * runners, isolator-backed {@link com.agentbench.execution.runner.SuiteToolchainRunner}
 * implementations (Maven/JUnit, pytest, Jest), and Checkstyle/SpotBugs/PMD quality analysis.
 *
 * <p>Docker-only isolation is <strong>not</strong> production-safe for arbitrary untrusted
 * workloads (shared-kernel residual risk). Opt into gVisor via
 * {@code agentbench.execution.mode=gvisor} on Linux hosts with runsc, or Firecracker via
 * {@code mode=firecracker} on Linux+KVM (ADR-004). Both stronger modes are fail-fast when
 * tooling is missing. Not a certified multi-tenant SaaS claim.
 */
package com.agentbench.execution;
