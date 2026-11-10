# AgentBench — Portfolio checklist (honest)

**As of post-M9 S3-compatible artifact storage.** Marked items are evidenced in `main` / this repo. Deferred items are explicit non-claims.

## Done (M0–M9)

| Item | Evidence |
|---|---|
| Modular monolith (Java 21 / Boot 3.4) + Compose | README, `docker-compose.yml` |
| Health / readiness / liveness | Actuator probes; Compose + K8s templates |
| JWT local auth, orgs/RBAC, projects | M2 APIs + tests |
| Versioned benchmarks + hidden suites + RBAC | M3 |
| Durable evaluation state machine + outbox | M4 |
| Provider-independent agents (Fake / OpenAI-compat / HTTP / CLI stub) | M5 |
| Scoring + visible F2P/P2P/P2F/F2F; hidden post-only pass rate | M6 |
| Hardened Docker `RuntimeIsolator` + quality tools | M7 |
| Org-private analytics / leaderboard / exports | M8 |
| Recovery tests (baseline / cancel / timeout / retry policy / DLQ topology) | M9 tests |
| Graceful shutdown + prod-ish Compose overlay | `application.yml`, `docker-compose.prod.yml` |
| Kubernetes templates (deploy/svc/config/secrets) | `deploy/kubernetes/` |
| Ops runbook snippets + load-test **plan** + k6 smoke script | `docs/OPERATIONS.md`, `docs/PERFORMANCE.md` |
| Threat model + portfolio honesty | this file + threat model |
| S3-compatible artifact store (MinIO / AWS SDK v2) | `ArtifactStorage` + Compose `--profile s3`; `ARTIFACT_STORAGE=s3\|local` |

## Deferred / not claimed

| Item | Status |
|---|---|
| gVisor / Firecracker isolation | **Deferred** — interface ready (`RuntimeIsolator`); Docker residual risk remains |
| Public leaderboards | **Deferred** — org-private only (Q9) |
| OIDC / SSO | **Deferred** — local JWT only (Q1) |
| Postgres RLS | **Deferred** — app-layer org isolation (Q12) |
| Managed cloud Helm chart / operators | **Deferred** — raw manifests only |
| Certified hostile multi-tenant SaaS security | **Not claimed** |
| Measured production throughput / capacity numbers | **Not claimed** without attached runs |
| Live public hosted demo on Oracle Always Free | **Guide-ready** (`docs/DEPLOYMENT_ORACLE_ALWAYS_FREE.md`) — **not actually hosted** until Sai’s VM + SSH apply the runbook |
| Sonar as scoring authority | **Not claimed** — stub only |
| Full CODING_CLI production isolation | Skeleton only |
| NetworkPolicies / mTLS / WORM audit | Draft / later |
| PDF report stack | JSON/CSV only |

## Demo path

See root **README** — single entry for clone → Compose → demo.
