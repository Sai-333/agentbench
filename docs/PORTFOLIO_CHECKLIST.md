# AgentBench — Portfolio checklist (honest)

**As of post-M9 public leaderboards + signed embeds/Cache-Control + OIDC + SAML SP SSO + S3-compatible artifact storage + Postgres RLS + WORM/append-only audit storage + measured local k6 smoke + opt-in gVisor + opt-in Firecracker + Helm chart + Operator/CRD packaging + NetworkPolicies + optional Helm mTLS + optional RabbitMQ AMQPS + PDF exports.** Marked items are evidenced in `main` / this repo. Deferred items are explicit non-claims.

## Done (M0–M9 + deferred follow-ups landed)

| Item | Evidence |
|---|---|
| Modular monolith (Java 21 / Boot 3.4) + Compose | README, `docker-compose.yml` |
| Health / readiness / liveness | Actuator probes; Compose + K8s templates |
| JWT local auth, orgs/RBAC, projects | M2 APIs + tests |
| OIDC Google/GitHub beside local JWT | `AuthProvider` SPI; `docs/AUTH_OIDC.md`; WireMock IdP tests |
| SAML 2.0 SP enterprise SSO (config-driven) | `docs/AUTH_SAML.md`; Spring Security SAML2/OpenSAML; WireMock metadata + signed assertion tests; default off |
| Multi-IdP SAML admin UI / APIs (org-scoped) | Flyway V16 `saml_identity_providers`; OWNER/ADMIN CRUD; login lists enabled IdPs; env IdP fallback when DB empty; `SamlMultiIdpAdminIntegrationTest`; `docs/AUTH_SAML.md` |
| Signed SAML AuthnRequests / SP signing keys | Optional platform PEM/env (`AGENTBENCH_SAML_SIGNING_*`, default off); HTTP-Redirect `SigAlg`/`Signature`; SP metadata cert + `AuthnRequestsSigned`; `SamlSpSigningIntegrationTest` + `SamlAuthnRequestFactoryTest`; `docs/AUTH_SAML.md` |
| Versioned benchmarks + hidden suites + RBAC | M3 |
| Durable evaluation state machine + outbox | M4 |
| Provider-independent agents (Fake / OpenAI-compat / HTTP / CODING_CLI) | M5 + isolator-backed CODING_CLI (`docs/AGENTS_CODING_CLI.md`) |
| Scoring + visible F2P/P2P/P2F/F2F; hidden post-only pass rate | M6 |
| Hardened Docker `RuntimeIsolator` + quality tools | M7 |
| Opt-in gVisor (`runsc`) behind `RuntimeIsolator` | `mode=gvisor` → `docker --runtime=runsc`; fail-fast without runsc; ADR-004; Mac stays Docker |
| Opt-in Firecracker microVM behind `RuntimeIsolator` | `mode=firecracker` → documented `agentbench-fc-run` wrapper + firecracker/jailer; fail-fast without binary/KVM; `scripts/firecracker/`; ADR-004; Mac stays Docker; **residual:** needs Linux+KVM host + guest assets; not certified multi-tenant SaaS |
| Org-private analytics / leaderboard / exports | M8 |
| PDF evaluation / leaderboard report export | OpenPDF; `GET .../evaluations/{id}/export?format=pdf` + `.../leaderboard/export?format=pdf`; VIEWER RBAC; UI Export PDF; `ReportExportServiceTest` + `AnalyticsDashboardTest` |
| Opt-in public leaderboards (OWNER/ADMIN publish; sanitized payload) | Flyway V11; `/api/v1/public/leaderboards/{slug}`; org-private remains default |
| Signed embeds + Cache-Control for public boards | HMAC embed tokens (OWNER/ADMIN mint); `/embed?token=`; Cache-Control + ETag; UI copy snippet; `docs/PUBLIC_LEADERBOARDS.md` |
| Anonymous-capable comments + up/down votes on public boards | Flyway V21; public POST/GET comments + votes on published slugs only; optional JWT attribution; OWNER/ADMIN soft-delete; IP rate + size limits; UI on `/public/leaderboards/{slug}`; embed HTML read-only tallies; `docs/PUBLIC_LEADERBOARDS.md` — **residual:** in-process rate limits (not distributed WAF/CAPTCHA); board-level votes only (not per-entry/stars) |
| Recovery tests (baseline / cancel / timeout / retry policy / DLQ topology) | M9 tests |
| Graceful shutdown + prod-ish Compose overlay | `application.yml`, `docker-compose.prod.yml` |
| Kubernetes templates (deploy/svc/config/secrets) | `deploy/kubernetes/` |
| Helm chart (API/worker/frontend) | `deploy/helm/agentbench/`; `scripts/helm/smoke.sh`; CI `helm` job; `docs/DEPLOYMENT.md` §3b |
| Operators / CRDs (Helm-operator packaging) | `deploy/operator/` — `AgentBenchInstance` CRD + helm-operator watches/Dockerfile + operator Helm chart; `scripts/operator/smoke.sh`; `docs/DEPLOYMENT.md` §3e — **not** certified multi-cloud SaaS |
| Kubernetes NetworkPolicies (default-deny + allow-needed) | `deploy/kubernetes/networkpolicy.yaml`; Helm `networkPolicy.*` (default on); `docs/DEPLOYMENT.md` §3c; smoke asserts render/disable |
| Ops runbook snippets + load-test **plan** + k6 smoke script | `docs/OPERATIONS.md`, `docs/PERFORMANCE.md` |
| Measured **local Compose** k6 smoke (laptop; not production) | `docs/PERFORMANCE.md` dated 2026-11-10 section; `deploy/loadtest/results/measured-2026-11-10-*` |
| Threat model + portfolio honesty | this file + threat model |
| S3-compatible artifact store (MinIO / AWS SDK v2) | `ArtifactStorage` + Compose `--profile s3`; `ARTIFACT_STORAGE=s3\|local` |
| Postgres RLS defense-in-depth | Flyway V12; `agentbench_app` + session GUC; `PostgresRlsIsolationTest`; `docs/POSTGRES_RLS.md` |
| Demo screenshots + short walkthrough recording | Project store `media/demo/` (login, org/projects, benchmarks, evaluation detail, analytics, public leaderboard) |
| CODING_CLI via RuntimeIsolator | `CodingCliAgentProvider`; config/image/argv; isolator invoke; timeouts; patch capture; `docs/AGENTS_CODING_CLI.md`; residual = active RuntimeIsolator (not hostile multi-tenant SaaS) |
| Optional SonarQube/SonarCloud quality path | Default off; report import + Web API client; WireMock/fixture tests; `docs/QUALITY_SONAR.md`; Checkstyle/SpotBugs/PMD remain primary |
| WORM / append-only audit storage | Flyway V15; deny UPDATE/DELETE triggers + `agentbench_app` INSERT/SELECT-only on `evaluation_state_transitions` + `platform.worm_audit_events`; per-org SHA-256 hash chain; dual-write from transitions; `WormAuditImmutabilityTest`; `docs/AUDIT_WORM.md` — **residual:** Postgres superuser can disable triggers; Object Lock is a separate opt-in layer |
| Optional S3 Object Lock / Compliance-mode WORM path | Opt-in `agentbench.artifacts.s3.object-lock` (default off); put retention + refuse overwrite/delete; worm audit export watermark Flyway V22; fail-fast bucket probe; Helm/Compose knobs; unit tests (`S3ObjectLock*`, `WormAuditS3ExportServiceTest`); `docs/AUDIT_WORM.md` — **residual:** default Compose MinIO is not lock-enabled; CI has no live AWS Compliance bucket; MinIO ≠ certified AWS Compliance; must deploy Object Lock-enabled bucket to claim Compliance in prod |
| Optional mTLS between services (Helm/K8s) | Helm `mtls.*` (default off); cert-manager Certificates **or** `scripts/helm/generate-mtls-certs.sh`; Spring Boot PEM SSL on API/worker; frontend nginx HTTPS proxy overlay; `docs/DEPLOYMENT.md` §3d — **residual:** not a service mesh; Mac Compose stays non-mTLS; not certified SaaS |
| Optional RabbitMQ AMQPS (TLS) | Opt-in `amqps.*` / `SPRING_RABBITMQ_SSL_*` (default off); `scripts/rabbitmq/generate-amqps-certs.sh` + `docker-compose.amqps.yml`; Helm broker TLS + PEM SSL bundle; NetworkPolicy 5671; `RabbitAmqpsConfiguration` fail-fast; `docs/DEPLOYMENT.md` §3f — **residual:** not Istio/Linkerd; Mac Compose stays plain AMQP; management UI HTTP in lab; no live AMQPS cluster in CI |
| Python (pytest) + Node (Jest) suite runners | `SuiteToolchainRunner` SPI; Maven/JUnit + pytest + Jest via `RuntimeIsolator`; JUnit XML ingest; `deploy/runners/`; `docs/RUNNERS.md`; F2P semantics unchanged; hidden post-only — **residual:** custom python/node images must be built/pulled |
| Opt-in Python (ruff) + Node (eslint) quality scanners | Default off; report import → `RuntimeIsolator` (QUALITY stage) → builtin heuristics; Flyway V20 comment marker; `docs/QUALITY_LANGUAGE.md`; Checkstyle/SpotBugs/PMD remain primary for Java; Sonar remains optional — **residual:** runner images must ship ruff/eslint for isolator path; builtins ≠ full linters |
| Artifact TTL + GC (R9) | Configurable retention (`AGENTBENCH_ARTIFACT_RETENTION_*`); scheduled GC purges expired patch blobs + DB metadata (`purged_at`, Flyway V17) and aged invocation URIs; orphan sweep on local + S3 `ArtifactStorage`; workspace TTL; `platform.artifact_cleanup_runs.deleted_metadata`; size ceilings unchanged; `docs/OPERATIONS.md` — **residual:** not S3 bucket lifecycle rules; no per-org TTL; Object Lock artifacts skip delete/sweep while protection is on |
| Artifact secret scanning (R7) | `ArtifactSecretScanner` + `ScanningArtifactStorage` on persist; redact (default) or quarantine; built-in patterns (API/AWS/PEM/JWT/GitHub/Slack/env dumps) + optional extra regexes; audit `platform.artifact_secret_findings` (Flyway V18, no raw secrets); `docs/OPERATIONS.md` — **residual:** not full DLP; binary non-text skipped; no per-org scan policy |
| Flake quarantine + baseline confirmation gates (R5) | Flyway V19 `flake_quarantines` + `baseline_confirmed`; OWNER/ADMIN CRUD; task/suite quarantine sources; scoring excludes quarantined; opt-in confirmation gate (`BASELINE_UNCONFIRMED`); `docs/SCORING_METHODOLOGY.md` — **residual:** not automatic ML flake discovery; gate default off |
| Selenium regression runs on push | Flyway V23 `platform.regression_runs` + cases; **AgentBench application** regression (platform-scoped, no `org_id`, no RLS — not per-consumer); OWNER/ADMIN of any org; ingest JUnit XML / pytest JSON / canonical cases with counts taken from case rows; top-bar **Regression runs** UI (outside org workspace tabs); `.github/workflows/selenium-regression.yml` clones `agentbench-selenium` `main` at run time (suite SHA not pinned), seeds `fixtures/mock-data.json` via `tests/seed.py`, runs the full pytest suite, POSTs the JUnit report, uploads it, and fails the job if any case failed; `docs/REGRESSION_RUNS.md` — **residual:** GitHub-hosted Ubuntu, not a Mac runner; does not cover Oracle, Firecracker, gVisor, or S3; Mac Compose remains the local default |

## Deferred / not claimed

| Item | Status |
|---|---|
| Firecracker on non-KVM / Mac Compose | **Residual** — Firecracker opt-in landed; live microVM needs Linux+KVM; Mac Compose stays Docker |
| Certified multi-cloud / multi-tenant SaaS Helm | **Not claimed** — Operators/CRDs packaging landed (`deploy/operator/`); no certification / multi-cloud SaaS claim |
| Certified hostile multi-tenant SaaS security | **Not claimed** — Docker residual risk; gVisor/Firecracker opt-in stronger but not a formal certification |
| Measured **production** throughput / capacity numbers | **Not claimed** — only attached local Compose laptop smoke exists |
| Live public hosted demo on Oracle Always Free | **Guide-ready** (`docs/DEPLOYMENT_ORACLE_ALWAYS_FREE.md`) — **not actually hosted** until Sai’s VM + SSH apply the runbook |
| Sonar as sole scoring authority | **Not claimed** — optional Sonar integration landed; primary tools remain Checkstyle/SpotBugs/PMD |
| Full hostile multi-tenant CODING_CLI / untrusted public CLI images | **Not claimed** — isolator-backed CODING_CLI landed; residual matches active RuntimeIsolator (Docker/gVisor/Firecracker) |
| Full service-mesh mTLS product (Istio/Linkerd) | **Not claimed** — optional app-level Helm mTLS (HTTP) + optional AMQPS landed; no Istio/Linkerd / automatic sidecar identity |
| Live AWS Compliance-mode Object Lock in default Compose / production demo | **Not claimed** — optional Object Lock code path landed; default MinIO/Helm leave it off; no hosted Compliance bucket asserted |
| Full CDN / edge network product | **Not claimed** — Cache-Control + ETag are CDN-friendly; no managed CDN required or asserted |
| Distributed anti-spam / CAPTCHA / ML moderation for public boards | **Not claimed** — comments/votes landed with size limits, in-process IP rate limits, OWNER/ADMIN soft-delete; not a full abuse platform |

## Demo path

See root **README** — single entry for clone → Compose → demo.
