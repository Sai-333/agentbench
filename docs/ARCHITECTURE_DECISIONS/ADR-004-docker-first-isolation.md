# ADR-004: Docker-First Isolation with Optional gVisor / Firecracker

**Status:** Accepted (M7 hardened Docker; post-M9 opt-in gVisor/runsc; post-M9 opt-in Firecracker microVM behind `RuntimeIsolator`)  
**Date:** 2026-11-09 (updated 2026-11-10)  
**Deciders:** Sai (approved with M0 decisions)

## Context

Executing untrusted patches requires isolation. Firecracker/gVisor offer stronger boundaries but increase ops complexity. Early milestones need a workable runner for demos and CI. Sai’s primary laptop is Mac-only (Docker Desktop / Compose); cloud Linux workers can install `runsc` or Firecracker+KVM.

## Decision

- **Default:** Docker Engine isolation with hardened run flags via `RuntimeIsolator`:
  - No Docker socket in workloads
  - Resource limits, non-root, capability drop, optional read-only rootfs
  - Egress default deny / allowlist
  - Image digest allowlist
- **Opt-in gVisor:** `agentbench.execution.mode=gvisor` uses the same hardened `docker run` flags plus `--runtime=runsc` (configurable via `agentbench.execution.gvisor-runtime`).
  - **Fail-fast** when gVisor mode is selected but Docker and/or `runsc` are unavailable — no silent fallback to Docker or local.
  - Mac / Compose demos stay on `mode=docker`.
- **Opt-in Firecracker:** `agentbench.execution.mode=firecracker` invokes a documented host wrapper (`agentbench-fc-run`; see `scripts/firecracker/agentbench-fc-run.sh`) that drives the `firecracker` binary and optional `jailer`.
  - **Fail-fast** when Firecracker mode is selected but the `firecracker` binary, KVM device (`/dev/kvm`), and/or wrapper are unavailable — no silent fallback.
  - Requires a Linux host with KVM. Live smoke is optional (`@EnabledIf`); CI without KVM must not claim a live microVM.
  - Mac / Compose demos stay on `mode=docker`.
- **Local mode:** `mode=local` forces host-process isolation for CI without a daemon (not a security boundary).
- Document residual shared-kernel risk for Docker-only in the threat model; do not claim certified hostile multi-tenant safety. Firecracker opt-in is stronger isolation but **not** a certified multi-tenant SaaS bar.
- **CODING_CLI** agent invocations use the same `RuntimeIsolator` path (`ExecutionStage.AGENT`) with optional per-agent image override — never a raw host shell (see `docs/AGENTS_CODING_CLI.md`).
- **Suite toolchains** (Maven/JUnit, pytest, Jest) use the same isolator for `VISIBLE_TESTS` / `HIDDEN_TESTS` with per-language image overrides (`docs/RUNNERS.md`).

## Configuration

| Knob | Default | Meaning |
|---|---|---|
| `AGENTBENCH_EXECUTION_MODE` / `agentbench.execution.mode` | `docker` | `docker` \| `gvisor` \| `firecracker` \| `local` |
| `AGENTBENCH_GVISOR_RUNTIME` | `runsc` | Docker runtime name (`docker run --runtime …`) |
| `AGENTBENCH_RUNSC_BINARY` | `runsc` | Binary probed via `runsc --version` |
| `AGENTBENCH_FIRECRACKER_WRAPPER` | `agentbench-fc-run` | Documented wrapper entry point |
| `AGENTBENCH_FIRECRACKER_BINARY` | `firecracker` | Binary probed via `firecracker --version` |
| `AGENTBENCH_JAILER_BINARY` | `jailer` | Optional jailer when use-jailer is true |
| `AGENTBENCH_FIRECRACKER_USE_JAILER` | `false` | Also probe/pass jailer |
| `AGENTBENCH_KVM_DEVICE` | `/dev/kvm` | Character device probed for KVM |

Enable gVisor on a Linux Docker host with gVisor installed and registered:

```bash
# After installing runsc and configuring /etc/docker/daemon.json runtimes.runsc
export AGENTBENCH_EXECUTION_MODE=gvisor
```

Enable Firecracker on a Linux+KVM host:

```bash
# Install firecracker (+ optional jailer), put scripts/firecracker/agentbench-fc-run.sh on PATH as agentbench-fc-run
# Provide guest kernel/rootfs for live runs:
#   export AGENTBENCH_FC_KERNEL=/path/to/vmlinux
#   export AGENTBENCH_FC_ROOTFS=/path/to/rootfs.ext4
export AGENTBENCH_EXECUTION_MODE=firecracker
```

If probes fail, the worker refuses to start eval stages with a message pointing operators back to `docker`, `gvisor`, or `local`.

## Consequences

**Positive:** Fast path to working evals; Compose-friendly; portable SPI; stronger sandbox opt-ins without Mac breakage.  
**Negative:** Docker residual breakout risk remains the default; gVisor needs Linux + runsc; Firecracker needs Linux + KVM + guest assets.  
**Neutral:** Runner images remain reusable under the gVisor runtime class; Firecracker guest rootfs is operator-supplied.

## Alternatives considered

- Firecracker-only from day one — rejected as M1–M6 blocker; now available as opt-in.  
- Bare host processes — rejected as primary path (kept as CI `local` only).  
- Remote SaaS sandboxes only — optional future adapter, not primary.  
- Silent Docker fallback when gVisor/Firecracker missing — rejected; prefer fail-fast so operators notice misconfiguration.  
- Docker `--runtime=firecracker` via firecracker-containerd only — rejected as the sole path; documented wrapper keeps the SPI honest when containerd runtime registration is absent.
