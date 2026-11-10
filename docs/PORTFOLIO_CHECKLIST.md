# AgentBench — Portfolio checklist (honest)

**As of post-M9 public leaderboards + OIDC auth + S3-compatible artifact storage + Postgres RLS + measured local k6 smoke + opt-in gVisor + Helm chart.** Marked items are evidenced in `main` / this repo. Deferred items are explicit non-claims.

## Done (M0–M9 + deferred follow-ups landed)

| Item | Evidence |
|---|---|
| Modular monolith (Java 21 / Boot 3.4) + Compose | README, `docker-compose.yml` |
| Health / readiness / liveness | Actuator probes; Compose + K8s templates |
| JWT local auth, orgs/RBAC, projects | M2 APIs + tests |
| OIDC Google/GitHub beside local JWT | `AuthProvider` SPI; `docs/AUTH_OIDC.md`; WireMock IdP tests |
| Versioned benchmarks + hidden suites + RBAC | M3 |
| Durable evaluation state machine + outbox | M4 |
| Provider-independent agents (Fake / OpenAI-compat / HTTP / CLI stub) | M5 |
| Scoring + visible F2P/P2P/P2F/F2F; hidden post-only pass rate | M6 |
| Hardened Docker `RuntimeIsolator` + quality tools | M7 |
| Opt-in gVisor (`runsc`) behind `RuntimeIsolator` | `mode=gvisor` → `docker --runtime=runsc`; fail-fast without runsc; ADR-004; Mac stays Docker |
| Org-private analytics / leaderboard / exports | M8 |
| Opt-in public leaderboards (OWNER/ADMIN publish; sanitized payload) | Flyway V11; `/api/v1/public/leaderboards/{slug}`; org-private remains default |
| Recovery tests (baseline / cancel / timeout / retry policy / DLQ topology) | M9 tests |
| Graceful shutdown + prod-ish Compose overlay | `application.yml`, `docker-compose.prod.yml` |
| Kubernetes templates (deploy/svc/config/secrets) | `deploy/kubernetes/` |
| Helm chart (API/worker/frontend; not operators/CRDs) | `deploy/helm/agentbench/`; `scripts/helm/smoke.sh`; CI `helm` job; `docs/DEPLOYMENT.md` §3b |
| Ops runbook snippets + load-test **plan** + k6 smoke script | `docs/OPERATIONS.md`, `docs/PERFORMANCE.md` |
| Measured **local Compose** k6 smoke (laptop; not production) | `docs/PERFORMANCE.md` dated 2026-11-10 section; `deploy/loadtest/results/measured-2026-11-10-*` |
| Threat model + portfolio honesty | this file + threat model |
| S3-compatible artifact store (MinIO / AWS SDK v2) | `ArtifactStorage` + Compose `--profile s3`; `ARTIFACT_STORAGE=s3\|local` |
| Postgres RLS defense-in-depth | Flyway V12; `agentbench_app` + session GUC; `PostgresRlsIsolationTest`; `docs/POSTGRES_RLS.md` |
| Demo screenshots + short walkthrough recording | Project store `media/demo/` (login, org/projects, benchmarks, evaluation detail, analytics, public leaderboard) |

## Deferred / not claimed

| Item | Status |
|---|---|
| Firecracker microVM isolation | **Deferred** — gVisor opt-in landed; Firecracker not implemented |
| Full enterprise SSO (SAML / multi-IdP admin UI) | **Deferred** — Google/GitHub OIDC authorization-code only (Q1 partial) |
| Operators / CRDs / certified multi-cloud SaaS Helm | **Deferred** — app Helm chart landed; no operators/CRDs |
| Certified hostile multi-tenant SaaS security | **Not claimed** — Docker residual risk; gVisor opt-in stronger but not a formal certification |
| Measured **production** throughput / capacity numbers | **Not claimed** — only attached local Compose laptop smoke exists |
| Live public hosted demo on Oracle Always Free | **Guide-ready** (`docs/DEPLOYMENT_ORACLE_ALWAYS_FREE.md`) — **not actually hosted** until Sai’s VM + SSH apply the runbook |
| Sonar as scoring authority | **Not claimed** — stub only |
| Full CODING_CLI production isolation | Skeleton only |
| NetworkPolicies / mTLS / WORM audit | Draft / later |
| PDF report stack | JSON/CSV only |
| Auto-updating public boards / signed embeds / CDN caching | **Not claimed** — live query of published slug only |
| Anonymous write / vote / comment on public boards | **Not claimed** — read-only public GET |

## Demo path

See root **README** — single entry for clone → Compose → demo.
