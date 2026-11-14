# AgentBench

Multi-tenant platform for evaluating coding agents against versioned benchmarks with visible and hidden tests.

**Current milestone:** M9 — Production readiness (final planned milestone).  
**Honesty:** Default Docker isolation remains lab/demo grade for untrusted workloads; optional Linux gVisor (`runsc`) and Firecracker (Linux+KVM) are available behind `RuntimeIsolator` — see [docs/PORTFOLIO_CHECKLIST.md](docs/PORTFOLIO_CHECKLIST.md) and ADR-004.

## Clone → Compose → demo

```bash
git clone https://github.com/Sai-333/agentbench.git
cd agentbench
cp .env.example .env
docker compose up -d --build
```

| Service | URL |
|---|---|
| Frontend | http://localhost:3000 |
| Backend API | http://localhost:8080 |
| Liveness | http://localhost:8080/actuator/health/liveness |
| Readiness | http://localhost:8080/actuator/health/readiness |
| Swagger UI | http://localhost:8080/swagger-ui.html |
| OpenAPI JSON | http://localhost:8080/v3/api-docs |
| RabbitMQ management | http://localhost:15672 (user/pass from `.env`) |

Quick API check:

```bash
curl -sf http://localhost:8080/api/v1/info
curl -sf -X POST http://localhost:8080/api/v1/auth/register \
  -H 'Content-Type: application/json' \
  -d '{"email":"demo@example.com","password":"password123","displayName":"Demo"}'
```

Open the frontend, sign in, create an org/project, publish a benchmark version, and run an evaluation. If you do not pick another agent, the run uses **Grok**. `XAI_API_KEY` calls `https://api.x.ai/v1` and wins when set. When it is empty, `CURSOR_API_KEY` (a legacy Cursor user API key) calls `POST https://api.cursor.com/v1/agents` with model id `grok-4.7` (`GROK_MODEL` can be `grok-4.6` or `grok-4.5`). That Cursor key is not sent to xAI. Register a **FAKE** agent for a local demo when no key is configured. Org-private analytics live under the dashboard tabs. See [docs/AGENTS.md](docs/AGENTS.md).

### Production-ish Compose overlay

```bash
# Set strong POSTGRES_PASSWORD, RABBITMQ_PASSWORD, AGENTBENCH_JWT_SECRET,
# AGENTBENCH_SECRETS_ENCRYPTION_KEY in .env first.
docker compose -f docker-compose.yml -f docker-compose.prod.yml up -d --build
```

### Deploy on Oracle Always Free

Single-host Ampere A1 runbook (VM shape, ports, Docker, secrets, health checks, TLS/firewall):  
[docs/DEPLOYMENT_ORACLE_ALWAYS_FREE.md](docs/DEPLOYMENT_ORACLE_ALWAYS_FREE.md) · helpers in `scripts/oracle/`.

**No live public URL is claimed** until Sai provisions an Always Free VM and applies that guide (needs OCID/IP/SSH).

## Stack

| Layer | Choice |
|---|---|
| Backend | Java 21, Spring Boot 3.4, Maven |
| Frontend | React, TypeScript, Vite |
| Data | PostgreSQL 16, Flyway (single forward-only stream) |
| Cache | Redis 7 |
| Messaging | RabbitMQ 3.13 (evaluation commands + DLQ); optional AMQPS TLS (`docs/DEPLOYMENT.md` §3f) |
| Auth | Local email/password + JWT; optional Google/GitHub OIDC (`docs/AUTH_OIDC.md`); optional SAML 2.0 SP (`docs/AUTH_SAML.md`) |
| API docs | springdoc OpenAPI + Swagger UI |
| Local runtime | Docker Compose (+ `docker-compose.prod.yml`) |
| Artifacts | `ArtifactStorage`: local volume (default) or S3-compatible (`ARTIFACT_STORAGE=s3`, Compose `--profile s3` / MinIO) |
| Eval isolation | Hardened Docker via `RuntimeIsolator` (default); optional `mode=gvisor` (`docker --runtime=runsc`) or `mode=firecracker` (Linux+KVM); local CI fallback |
| Suite runners | Fixture-v1 + Maven/JUnit, pytest, Jest via `SuiteToolchainRunner` + isolator (`docs/RUNNERS.md`); hidden stays post-only |
| Quality scanners | Checkstyle/SpotBugs/PMD primary; optional Sonar + opt-in ruff/eslint (`docs/QUALITY_SONAR.md`, `docs/QUALITY_LANGUAGE.md`) |
| K8s | Templates under `deploy/kubernetes/` (incl. NetworkPolicies); Helm chart `deploy/helm/agentbench/` (optional `mtls.*`, `amqps.*`); optional Operator/CRD `deploy/operator/` (`AgentBenchInstance`); not certified multi-cloud SaaS; not a service mesh |

## Architecture

Single Spring Boot deployable with modular package boundaries:

`identity` · `projects` · `benchmarks` · `agents` · `evaluation` · `execution` · `analytics` · `platform`

Tenant APIs use an explicit org base with membership checks on every request:

```text
/api/v1/orgs/{orgId}/...
```

Roles (org-scoped): `OWNER`, `ADMIN`, `DEVELOPER`, `VIEWER`.

Every push records an **AgentBench application** regression run (platform-scoped git repo, branch, and SHA — not consumer / org data) from `agentbench-selenium` `main`, with per-case pass/fail on the top-bar **Regression runs** page. Local helper: [`scripts/regression/run-local.sh`](scripts/regression/run-local.sh). See [docs/REGRESSION_RUNS.md](docs/REGRESSION_RUNS.md). Mac Compose stays the default local stack.

## Milestone 9 highlights

| Area | Behavior |
|---|---|
| Recovery | DLQ topology + listener retry; infra stage retry budget; cancel mid-flight; timeout watchdog; invalid baseline fails closed |
| Shutdown | `server.shutdown=graceful` + documented probes |
| Deploy | Compose prod overlay; Kubernetes templates + Helm chart (`deploy/helm/agentbench/`) + optional operator (`deploy/operator/`) |
| Perf | k6 smoke script + load-test **plan** (no unverified throughput claims) |
| Security review | Isolator constraints, hidden confidentiality, secret redaction + artifact secret scan (R7); threat model residual risks updated |
| Ops | Backup / migrate / upgrade / DLQ snippets in `docs/OPERATIONS.md` |
| Portfolio | Honest done vs deferred checklist |

**Explicit non-claims:** gVisor and Firecracker are **opt-in** (Linux+runsc / Linux+KVM; not Mac Compose default; not a certified hostile multi-tenant claim). Firecracker still needs a KVM host + guest assets (`scripts/firecracker/`). SAML SP login is optional (`docs/AUTH_SAML.md`) with org-scoped multi-IdP admin (OWNER/ADMIN) and optional SP AuthnRequest signing (default off). Postgres RLS is defense-in-depth beside app-layer org filters (`docs/POSTGRES_RLS.md`). Public leaderboards are **opt-in** (OWNER/ADMIN publish; org-private default) with optional HMAC-signed embeds + Cache-Control and anonymous-capable comments/up-down votes on published boards only (`docs/PUBLIC_LEADERBOARDS.md`) — no managed CDN; abuse controls are size/IP rate limits + OWNER/ADMIN soft-delete (not distributed WAF/CAPTCHA). Google/GitHub OIDC is optional beside local JWT (see `docs/AUTH_OIDC.md`). **CODING_CLI** is isolator-backed (`docs/AGENTS_CODING_CLI.md`) — not a certified hostile multi-tenant CLI sandbox. Optional Sonar is **not** the sole scoring authority (`docs/QUALITY_SONAR.md`). Opt-in Python (ruff) / Node (eslint) quality scanners are also **not** sole scoring authority (`docs/QUALITY_LANGUAGE.md`; default off). Python/Node runner images must be built/pulled on the worker (`deploy/runners/`, `docs/RUNNERS.md`) before those frameworks (or isolator quality scans) run under Docker — not a claim that every Compose boot includes them.

### Optional MinIO artifact store

```bash
ARTIFACT_STORAGE=s3 docker compose --profile s3 up -d --build
docker compose --profile s3 ps minio
```

See [docs/DEPLOYMENT.md](docs/DEPLOYMENT.md) for S3 env vars, retention, and cleanup.

## Local development

### Backend

```bash
cd backend
mvn -B verify
mvn spring-boot:run
```

Requires local Postgres, Redis, and RabbitMQ (or Compose infra only):

```bash
docker compose up -d postgres redis rabbitmq
```

### Frontend

```bash
cd frontend
npm ci
npm run build
npm run dev
```

## Configuration

See `.env.example`. Do not commit real secrets.

Notable variables:

- `AGENTBENCH_JWT_SECRET` (≥ 32 chars)
- `AGENTBENCH_SECRETS_ENCRYPTION_KEY` (base64 32-byte AES key)
- `AGENTBENCH_EXECUTION_MODE` (`docker` \| `gvisor` \| `firecracker` \| `local`) — default `docker`; `gvisor`/`firecracker` fail-fast without tooling/KVM
- `AGENTBENCH_GVISOR_RUNTIME` / `AGENTBENCH_RUNSC_BINARY` (Linux gVisor; defaults `runsc`)
- `AGENTBENCH_RUNNER_IMAGE` / `AGENTBENCH_RUNNER_IMAGE_DIGEST`
- `AGENTBENCH_EXEC_MEMORY_MB` / `AGENTBENCH_EXEC_CPU` / `AGENTBENCH_EXEC_PIDS` / `AGENTBENCH_EXEC_TIMEOUT_SEC`
- `ARTIFACT_STORAGE` (`local` \| `s3`) + `AGENTBENCH_S3_*` when using MinIO/S3
- `AGENTBENCH_ARTIFACT_RETENTION_ENABLED` / `AGENTBENCH_ARTIFACT_RETENTION_DAYS` / `AGENTBENCH_ARTIFACT_SWEEP_MS` / `AGENTBENCH_ARTIFACT_MAX_LOG_BYTES` / `AGENTBENCH_ARTIFACT_MAX_OBJECT_BYTES` (TTL + GC; see `docs/OPERATIONS.md`)
- `AGENTBENCH_ARTIFACT_SECRET_SCAN_ENABLED` / `AGENTBENCH_ARTIFACT_SECRET_SCAN_MODE` (`REDACT`\|`QUARANTINE`) — pattern scan on artifact persist (not full DLP; see `docs/OPERATIONS.md`)
- `AGENTBENCH_EVAL_FLAKY_REPEAT_RUNS`
- `AGENTBENCH_EVAL_BASELINE_CONFIRMATION_ENABLED` / `AGENTBENCH_EVAL_BASELINE_CONFIRMATION_RUNS` (R5 gate; see `docs/SCORING_METHODOLOGY.md`)
- `AGENTBENCH_SHUTDOWN_TIMEOUT` / `AGENTBENCH_RABBIT_LISTENER_MAX_ATTEMPTS`
- `XAI_API_KEY` — direct key for the default Grok agent (`https://api.x.ai/v1`). Leave empty for FAKE-only demos. Never commit a real key.
- `CURSOR_API_KEY` — legacy Cursor user API key (Dashboard → API Keys). Used for Grok only when `XAI_API_KEY` is empty. Calls `POST https://api.cursor.com/v1/agents` with `model.id` `grok-4.7`, `grok-4.6`, or `grok-4.5`. Not sent to xAI.
- `GROK_MODEL` — default `grok-4.7`

## Flyway

Single migration stream under `backend/src/main/resources/db/migration`.

- `V1`–`V8` — platform through analytics indexes
- `V9__production_readiness.sql` — recovery indexes + M9 marker
- `V17__artifact_ttl_gc.sql` — patch `purged_at` + cleanup `deleted_metadata` for TTL/GC
- `V18__artifact_secret_scan.sql` — `platform.artifact_secret_findings` audit (pattern ids/counts only)
- `V19__flake_quarantine_baseline_gates.sql` — `evaluations.flake_quarantines` + `test_runs.baseline_confirmed`

Never edit applied migrations; forward-only only.

## Docs map

| Doc | Topic |
|---|---|
| [docs/DEPLOYMENT.md](docs/DEPLOYMENT.md) | Compose + K8s + health/shutdown |
| [docs/DEPLOYMENT_ORACLE_ALWAYS_FREE.md](docs/DEPLOYMENT_ORACLE_ALWAYS_FREE.md) | Oracle Cloud Always Free VM path |
| [docs/OPERATIONS.md](docs/OPERATIONS.md) | Backup / migrate / DLQ |
| [docs/POSTGRES_RLS.md](docs/POSTGRES_RLS.md) | RLS defense-in-depth ops caveats |
| [docs/PERFORMANCE.md](docs/PERFORMANCE.md) | Load-test plan + k6 |
| [docs/SECURITY_THREAT_MODEL.md](docs/SECURITY_THREAT_MODEL.md) | Threat model |
| [docs/ARCHITECTURE_DECISIONS/ADR-004-docker-first-isolation.md](docs/ARCHITECTURE_DECISIONS/ADR-004-docker-first-isolation.md) | Docker default + optional gVisor |
| [docs/PORTFOLIO_CHECKLIST.md](docs/PORTFOLIO_CHECKLIST.md) | Done vs deferred |
| [docs/SCORING_METHODOLOGY.md](docs/SCORING_METHODOLOGY.md) | F2P/P2P/P2F/F2F, hidden post-only, flake quarantine, baseline gates |
| [docs/QUALITY_SONAR.md](docs/QUALITY_SONAR.md) | Optional SonarQube/SonarCloud quality path |
| [docs/QUALITY_LANGUAGE.md](docs/QUALITY_LANGUAGE.md) | Optional Python (ruff) / Node (eslint) quality scanners |
| [docs/AGENTS.md](docs/AGENTS.md) | Agent providers; Grok is the default |
| [docs/RUNNERS.md](docs/RUNNERS.md) | Suite runners (Maven / pytest / Jest) |
| [deploy/kubernetes/](deploy/kubernetes/) | K8s templates |
| [deploy/helm/agentbench/](deploy/helm/agentbench/) | Helm chart (install/upgrade/uninstall) |
| [deploy/operator/](deploy/operator/) | AgentBenchInstance CRD + helm-operator packaging |
| [scripts/helm/smoke.sh](scripts/helm/smoke.sh) | `helm lint` + `helm template` + operator smoke |
| [scripts/operator/smoke.sh](scripts/operator/smoke.sh) | CRD / watches / operator chart lint |
| [deploy/loadtest/k6-smoke.js](deploy/loadtest/k6-smoke.js) | Smoke script |

## CI

GitHub Actions builds backend (`mvn verify`, including Testcontainers when Docker is available), frontend (`npm ci && npm run build`), and Helm/operator packaging (`./scripts/helm/smoke.sh`, including `./scripts/operator/smoke.sh`). WireMock covers OpenAI-compatible HTTP, including the default Grok agent, without paid APIs.

**Docker 29 note:** Engine 29 defaults to a minimum API of 1.44 while Testcontainers 1.21.x still negotiates an older client API. Local/daemon workaround:

```bash
echo '{"min-api-version":"1.24"}' | sudo tee /etc/docker/daemon.json
# restart dockerd
```

## Security notes

- Never returns plaintext secrets, ciphertext, or raw tokens in API responses.
- Hidden suite fixtures are RBAC-gated; agent-facing projections never include them.
- Org isolation is enforced in the application/repository layer, with Postgres RLS as defense-in-depth (`docs/POSTGRES_RLS.md`).
- Evaluation containers never receive `docker.sock` or `--privileged`.
- Docker-only is lab/demo grade; prefer opt-in gVisor or Firecracker on suitable Linux hosts (see threat model / ADR-004).

## License

TBD
