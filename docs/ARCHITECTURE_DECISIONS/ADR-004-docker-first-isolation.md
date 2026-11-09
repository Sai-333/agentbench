# ADR-004: Docker-First Isolation with Stronger Isolation Later

**Status:** Accepted (M7 implements hardened Docker path; stronger isolation still planned)  
**Date:** 2026-11-09  
**Deciders:** Sai (approved with M0 decisions)

## Context

Executing untrusted patches requires isolation. Firecracker/gVisor offer stronger boundaries but increase ops complexity. Early milestones need a workable runner for demos and CI.

## Decision

- **v1 (M7):** Docker Engine isolation with hardened run flags via `RuntimeIsolator`:
  - No Docker socket in workloads
  - Resource limits, non-root, capability drop, optional read-only rootfs
  - Egress default deny / allowlist
  - Image digest allowlist
- **Later (M9+):** Spike and optionally adopt **gVisor** and/or **Firecracker** microVMs behind the same `RuntimeIsolator` interface so evaluation code does not bind to Docker API types forever.
- Document residual shared-kernel risk in the threat model; do not claim hostile multi-tenant safety on Docker alone.

## Consequences

**Positive:** Fast path to working evals; Compose-friendly; portable interface for upgrade.  
**Negative:** Known breakout residual risk on Docker; must restrict who can run untrusted agents early.  
**Neutral:** Runner images remain reusable under gVisor runtime classes.

## Alternatives considered

- Firecracker-only from day one — rejected as M1–M6 blocker.  
- Bare host processes — rejected (unacceptable trust).  
- Remote SaaS sandboxes only — optional future adapter, not primary.
