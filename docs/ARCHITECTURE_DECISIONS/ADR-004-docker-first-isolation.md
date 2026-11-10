# ADR-004: Docker-First Isolation with Optional gVisor

**Status:** Accepted (M7 hardened Docker; post-M9 opt-in gVisor/runsc behind `RuntimeIsolator`)  
**Date:** 2026-11-09 (updated 2026-11-10)  
**Deciders:** Sai (approved with M0 decisions)

## Context

Executing untrusted patches requires isolation. Firecracker/gVisor offer stronger boundaries but increase ops complexity. Early milestones need a workable runner for demos and CI. Sai’s primary laptop is Mac-only (Docker Desktop / Compose); cloud Linux workers can install `runsc`.

## Decision

- **Default:** Docker Engine isolation with hardened run flags via `RuntimeIsolator`:
  - No Docker socket in workloads
  - Resource limits, non-root, capability drop, optional read-only rootfs
  - Egress default deny / allowlist
  - Image digest allowlist
- **Opt-in gVisor:** `agentbench.execution.mode=gvisor` uses the same hardened `docker run` flags plus `--runtime=runsc` (configurable via `agentbench.execution.gvisor-runtime`).
  - **Fail-fast** when gVisor mode is selected but Docker and/or `runsc` are unavailable — no silent fallback to Docker or local.
  - Mac / Compose demos stay on `mode=docker`.
- **Local mode:** `mode=local` forces host-process isolation for CI without a daemon (not a security boundary).
- **Firecracker:** not implemented. Prefer gVisor; revisit microVMs only if runsc cannot meet a future hostile multi-tenant bar.
- Document residual shared-kernel risk for Docker-only in the threat model; do not claim certified hostile multi-tenant safety.
- **CODING_CLI** agent invocations use the same `RuntimeIsolator` path (`ExecutionStage.AGENT`) with optional per-agent image override — never a raw host shell (see `docs/AGENTS_CODING_CLI.md`).

## Configuration

| Knob | Default | Meaning |
|---|---|---|
| `AGENTBENCH_EXECUTION_MODE` / `agentbench.execution.mode` | `docker` | `docker` \| `gvisor` \| `local` |
| `AGENTBENCH_GVISOR_RUNTIME` | `runsc` | Docker runtime name (`docker run --runtime …`) |
| `AGENTBENCH_RUNSC_BINARY` | `runsc` | Binary probed via `runsc --version` |

Enable on a Linux Docker host with gVisor installed and registered:

```bash
# After installing runsc and configuring /etc/docker/daemon.json runtimes.runsc
export AGENTBENCH_EXECUTION_MODE=gvisor
```

If probes fail, the worker refuses to start eval stages with a message pointing operators back to `docker` or `local`.

## Consequences

**Positive:** Fast path to working evals; Compose-friendly; portable SPI; stronger sandbox opt-in without Mac breakage.  
**Negative:** Docker residual breakout risk remains the default; gVisor needs Linux + runsc ops.  
**Neutral:** Runner images remain reusable under the gVisor runtime class.

## Alternatives considered

- Firecracker-only from day one — rejected as M1–M6 blocker; still deferred.  
- Bare host processes — rejected as primary path (kept as CI `local` only).  
- Remote SaaS sandboxes only — optional future adapter, not primary.  
- Silent Docker fallback when gVisor missing — rejected; prefer fail-fast so operators notice misconfiguration.
