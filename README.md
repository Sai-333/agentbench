# AgentBench

Multi-tenant platform for evaluating coding agents against versioned benchmarks with visible and hidden tests.

**Current milestone:** M9 — Production readiness (final planned milestone).  
**Honesty:** Docker isolation remains lab/demo grade for untrusted workloads; see [docs/PORTFOLIO_CHECKLIST.md](docs/PORTFOLIO_CHECKLIST.md).

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

Open the frontend, sign in, create an org/project, publish a benchmark version, register a **FAKE** agent, and run an evaluation. Org-private analytics live under the dashboard tabs.

### Production-ish Compose overlay

```bash
# Set strong POSTGRES_PASSWORD, RABBITMQ_PASSWORD, AGENTBENCH_JWT_SECRET,
# AGENTBENCH_SECRETS_ENCRYPTION_KEY in .env first.
docker compose -f docker-compose.yml -f docker-compose.prod.yml up -d --build
```

## Stack

| Layer | Choice |
|---|---|
| Backend | Java 21, Spring Boot 3.4, Maven |
| Frontend | React, TypeScript, Vite |
| Data | PostgreSQL 16, Flyway (single forward-only stream) |
| Cache | Redis 7 |
| Messaging | RabbitMQ 3.13 (evaluation commands + DLQ) |
| Auth | Local email/password + JWT (OIDC deferred) |
| API docs | springdoc OpenAPI + Swagger UI |
| Local runtime | Docker Compose (+ `docker-compose.prod.yml`) |
| Eval isolation | Hardened Docker via `RuntimeIsolator` (local fallback for CI) |
| K8s | Templates under `deploy/kubernetes/` (no real secrets) |

## Architecture

Single Spring Boot deployable with modular package boundaries:

`identity` · `projects` · `benchmarks` · `agents` · `evaluation` · `execution` · `analytics` · `platform`

Tenant APIs use an explicit org base with membership checks on every request:

```text
/api/v1/orgs/{orgId}/...
```

Roles (org-scoped): `OWNER`, `ADMIN`, `DEVELOPER`, `VIEWER`.

## Milestone 9 highlights

| Area | Behavior |
|---|---|
| Recovery | DLQ topology + listener retry; infra stage retry budget; cancel mid-flight; timeout watchdog; invalid baseline fails closed |
| Shutdown | `server.shutdown=graceful` + documented probes |
| Deploy | Compose prod overlay; Kubernetes Deployment/Service/ConfigMap/Secret templates |
| Perf | k6 smoke script + load-test **plan** (no unverified throughput claims) |
| Security review | Isolator constraints, hidden confidentiality, secret redaction; threat model residual risks updated |
| Ops | Backup / migrate / upgrade / DLQ snippets in `docs/OPERATIONS.md` |
| Portfolio | Honest done vs deferred checklist |

**Explicit non-claims:** no gVisor/Firecracker yet; no public leaderboards; no OIDC; not certified hostile multi-tenant SaaS.

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
- `AGENTBENCH_EXECUTION_MODE` (`docker` \| `local`)
- `AGENTBENCH_RUNNER_IMAGE` / `AGENTBENCH_RUNNER_IMAGE_DIGEST`
- `AGENTBENCH_EXEC_MEMORY_MB` / `AGENTBENCH_EXEC_CPU` / `AGENTBENCH_EXEC_PIDS` / `AGENTBENCH_EXEC_TIMEOUT_SEC`
- `AGENTBENCH_ARTIFACT_RETENTION_DAYS` / `AGENTBENCH_ARTIFACT_MAX_LOG_BYTES`
- `AGENTBENCH_EVAL_FLAKY_REPEAT_RUNS`
- `AGENTBENCH_SHUTDOWN_TIMEOUT` / `AGENTBENCH_RABBIT_LISTENER_MAX_ATTEMPTS`

## Flyway

Single migration stream under `backend/src/main/resources/db/migration`.

- `V1`–`V8` — platform through analytics indexes
- `V9__production_readiness.sql` — recovery indexes + M9 marker

Never edit applied migrations; forward-only only.

## Docs map

| Doc | Topic |
|---|---|
| [docs/DEPLOYMENT.md](docs/DEPLOYMENT.md) | Compose + K8s + health/shutdown |
| [docs/OPERATIONS.md](docs/OPERATIONS.md) | Backup / migrate / DLQ |
| [docs/PERFORMANCE.md](docs/PERFORMANCE.md) | Load-test plan + k6 |
| [docs/SECURITY_THREAT_MODEL.md](docs/SECURITY_THREAT_MODEL.md) | Threat model |
| [docs/PORTFOLIO_CHECKLIST.md](docs/PORTFOLIO_CHECKLIST.md) | Done vs deferred |
| [deploy/kubernetes/](deploy/kubernetes/) | K8s templates |
| [deploy/loadtest/k6-smoke.js](deploy/loadtest/k6-smoke.js) | Smoke script |

## CI

GitHub Actions builds backend (`mvn verify`, including Testcontainers when Docker is available) and frontend (`npm ci && npm run build`). WireMock covers OpenAI-compatible HTTP without paid APIs.

**Docker 29 note:** Engine 29 defaults to a minimum API of 1.44 while Testcontainers 1.21.x still negotiates an older client API. Local/daemon workaround:

```bash
echo '{"min-api-version":"1.24"}' | sudo tee /etc/docker/daemon.json
# restart dockerd
```

## Security notes

- Never returns plaintext secrets, ciphertext, or raw tokens in API responses.
- Hidden suite fixtures are RBAC-gated; agent-facing projections never include them.
- Org isolation is enforced in the application/repository layer (Postgres RLS deferred).
- Evaluation containers never receive `docker.sock` or `--privileged`.
- Docker-only is lab/demo grade until gVisor/Firecracker (see threat model).

## License

TBD
