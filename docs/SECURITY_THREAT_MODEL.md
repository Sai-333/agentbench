# AgentBench — Security Threat Model

**Status:** Updated for Milestone 9 (production readiness review)  
**Method:** Informal STRIDE-oriented review for v1 (Docker-first). Not a formal certification artifact.

---

## 1. Trust boundaries

```text
[Browser/User] ──JWT──► [API Monolith] ──► [PostgreSQL / Redis / RabbitMQ]
                              │
                              ├──► [Agent Adapter / External LLM]
                              │
                              └──► [Trusted Runner Controller / Worker]
                                        │  (docker CLI; optional sock on WORKER only)
                                        ▼
                                   [Untrusted Eval Container]
                                   (patch + tests; NO docker.sock; NO privileged)
```

**Untrusted:** agent patches, partially author-supplied tests, model outputs, user uploads.  
**Trusted:** API/worker code, DB, queue, image allowlist, secret store keys, hidden fixture volume ACLs.

---

## 2. Assets

| Asset | Sensitivity |
|---|---|
| Hidden test fixtures & expected outcomes | Critical |
| Org secrets (Git, agent API keys) | Critical |
| Patches / proprietary repo snapshots | High |
| Evaluation scores & reports | Medium–High |
| JWT signing keys | Critical |
| Host Docker control plane | Critical |

---

## 3. Adversaries

1. Malicious or compromised coding agent (patch as exploit).
2. Malicious org member (exfiltrate hidden tests / secrets).
3. Cross-tenant attacker (IDOR / confused deputy).
4. Supply-chain: poisoned base image or dependency.
5. External LLM provider leakage (prompt logs) — residual risk.

---

## 4. STRIDE summary

| Threat | Example | Mitigation (v1 / M7–M9) | Later |
|---|---|---|---|
| **Spoofing** | Stolen JWT / agent callback token | Short-lived JWT; optional Google/GitHub OIDC + multi-IdP SAML SP (optional AuthnRequest signing); rotate agent secrets; TLS at edge; optional Helm east-west mTLS (`mtls.enabled`) | Mesh / AMQPS |
| **Tampering** | Mutate sealed patch mid-run | Content digest; seal flag; immutable storage | Signed artifacts |
| **Repudiation** | Deny hidden-test access | Audit log for suite reads & state transitions; Postgres WORM triggers + hash chain (`docs/AUDIT_WORM.md`) | S3 Object Lock export |
| **Info disclosure** | Hidden tests in agent prompt / logs | Separate stages; DTO filtering; `SecretRedactor`; RBAC | Dedicated hidden runner pool |
| **DoS** | Fork bomb / disk fill in container | Docker CPU/mem/pids/ulimit; timeouts; org quotas; DLQ | cgroup v2 + fair share |
| **Elevation** | Escape to host via Docker | No docker.sock in workload; non-root; dropped caps; read-only root FS; optional `mode=gvisor` (`--runtime=runsc`) or `mode=firecracker` (Linux+KVM) | Certified multi-tenant SaaS bar |

---

## 5. Critical controls checklist (verified in M7–M9)

1. **Never mount Docker socket** into evaluation containers (`RuntimeIsolator` rejects sock/privileged fragments).
2. **Network:** default deny egress from eval containers (`--network none`); allowlist only if task requires.
3. **Hidden tests:** loaded only in `RUNNING_HIDDEN_TESTS`; staged under a separate root; cleaned after the stage; not present during `INVOKING_AGENT`.
4. **Secrets:** encrypt at rest; never in API responses; `SecretRedactor` on logs/errors/reports/DLQ logs; `ArtifactSecretScanner` on artifact persist (patches/fixtures/invocation outputs) with redact or quarantine + audit (`platform.artifact_secret_findings`) — pattern-based, not full DLP.
5. **Org isolation:** every query constrained by `org_id`; automated IDOR tests.
6. **Resource limits** mandatory on every hardened `docker run` (CPU/mem/pids/time/ulimit).
7. **Image allowlist** — configurable runner image; optional digest pin (`image@sha256:…`).
8. **Patch size limits** and reject suspicious paths (`../`, absolute paths, `.git/hooks`, encoded traversal).
9. **Ephemeral containers** (`--rm`) + best-effort name cleanup after timeout/kill.
10. **Artifact retention** — configurable TTL + scheduled GC (blobs via local/S3 `ArtifactStorage`, patch DB metadata `purged_at`, invocation URI clear) + max log/object bytes; cleanup audit table.
10b. **Artifact secret scanning (R7)** — pattern scan at persist (`AGENTBENCH_ARTIFACT_SECRET_SCAN_*`); redact (default) or quarantine; audit without storing secret values.
11. **Recovery:** listener retry → DLQ; stage infra retry budget; cancel between stages; duration watchdog; invalid baseline fails closed.
12. **Health:** liveness/readiness probes; graceful shutdown for rolling updates.

---

## 6. Explicit non-claim (mandatory)

**Docker-only isolation is NOT production-safe for arbitrary untrusted workloads.**  
Shared-kernel breakout residual risk remains on the default path. Prefer **`agentbench.execution.mode=gvisor`** on Linux hosts with `runsc`, or **`mode=firecracker`** on Linux+KVM hosts, for stronger isolation (ADR-004). Both are **opt-in and fail-fast** when tooling is unavailable; Mac Compose stays on Docker. Firecracker opt-in is landed behind `RuntimeIsolator` but **still requires a KVM host** and is **not** a certified multi-tenant SaaS claim. **CODING_CLI** runs inside the same isolator (not host shell); residual risk matches the active runtime.

---

## 7. Hidden-test confidentiality scenario

**Attack:** Agent or evaluator role tries to read hidden fixtures via API or by writing a patch that `cat`s files.

**Defenses:**

- API omits hidden payloads for non-AUTHOR roles; AUTHORS audited.
- Hidden fixtures not in the workspace image used for agent context.
- Post-patch hidden run uses a fresh clone + fixtures staged outside the agent tree; mount only for `HIDDEN_TESTS`.
- Strip secrets/paths from collected logs; agents do not see hidden run logs as prompts.

---

## 8. Patch-as-malware scenario

**Attack:** Patch includes reverse shell or crypto miner.

**Defenses:**

- No egress (blocks most reverse shells).
- CPU/time/pids limits (miner / fork bomb).
- Non-root, cap-drop ALL, no-new-privileges, optional read-only rootfs + tmpfs `/tmp`.
- Residual risk: kernel exploits — accept for v1 lab use; escalate isolation before multi-tenant hostile agents.

---

## 9. Cross-tenant scenario

**Attack:** Swap `orgId` in path to read another org’s evaluation.

**Defenses:**

- Membership check on every controller.
- Integration tests with two orgs.
- Postgres FORCE RLS on tenant tables (`app.current_org_id` / controlled bypass); see `docs/POSTGRES_RLS.md`.

---

## 10. Code quality stage

- Tools: **Checkstyle + SpotBugs + PMD** (XML when present; builtin Java heuristics otherwise) — primary local path.
- **Sonar** is optional (workspace issues JSON import or Web API fetch when `AGENTBENCH_SONAR_ENABLED=true`); default off; never invents scores; not sole scoring authority. See `docs/QUALITY_SONAR.md`.
- Findings split into **newly introduced** vs **pre-existing** (fingerprint diff baseline↔post).
- `code_quality` metric uses a real normalized score only when tools produce results; otherwise `stub=true` and raw=0.

---

## 11. Residual risks (accepted; updated M9)

| Risk | Acceptance |
|---|---|
| Docker breakout on shared kernel | Lab / trusted authors only; enable `mode=gvisor` (Linux+runsc) or `mode=firecracker` (Linux+KVM) when stronger isolation is required |
| gVisor residual / misconfiguration | Fail-fast if `mode=gvisor` but runsc missing; still not a formal certification |
| Firecracker residual / needs KVM host | Opt-in landed; fail-fast without firecracker/KVM/wrapper; guest kernel+rootfs operator-supplied; not certified multi-tenant SaaS |
| LLM provider stores prompts | Prefer self-hosted or enterprise no-retention contracts |
| Author malice within own org | Org trust boundary; cannot fully prevent |
| Local isolator fallback | Used when Docker unavailable (CI); not a security boundary |
| K8s NetworkPolicy / mTLS residual | NetworkPolicies ship (L3/L4); optional Helm mTLS for HTTP (API/worker/frontend proxy) — not a mesh, not AMQPS; default `clientAuth=want`; Mac Compose plaintext |
| WORM residual (not Object Lock) | Append-only triggers + hash chain on audit tables; app role cannot UPDATE/DELETE; superuser can still disable triggers; AWS Object Lock not wired |
| AES secret-key rotation not automated | Dual-key re-encrypt before rotating encryption keys |
| Artifact secret scan is pattern-based only | Not full DLP; tune `additional-patterns` / use `QUARANTINE` for stricter demos; binary non-text skipped |
| Public boards opt-in only | Google/GitHub OIDC + multi-IdP SAML SP (optional SP signing) available; public leaderboards require OWNER/ADMIN publish + sanitized payload; embeds need HMAC token + expiry |

---

## 12. Security test plan

- Patch path traversal / `.git/hooks` / absolute path negative tests.
- Docker command builder asserts: no privileged, no docker.sock, network none, non-root, limits.
- gVisor command builder asserts `--runtime=runsc` plus the same hardening; fail-fast when mode=gvisor and probes miss.
- Firecracker wrapper command builder asserts documented `agentbench-fc-run` contract; fail-fast when mode=firecracker and firecracker/KVM/wrapper probes miss; live smoke `@EnabledIf` only.
- Secret redaction unit tests.
- Artifact secret scanner unit tests (fixtures must not retain raw secrets; quarantine messages omit values).
- RestAssured/MockMvc IDOR suites (org authorization regression).
- Quality parsers + new-vs-pre-existing aggregation tests.
- M9 recovery: invalid baseline, mid-flight cancel, timeout watchdog, retry policy, DLQ topology.
