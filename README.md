# AgentBench

Multi-tenant platform for evaluating coding agents against versioned benchmarks with visible and hidden tests.

**Current milestone:** M8 — Analytics and reporting dashboards (org-private; real backend aggregates).

## Stack

| Layer | Choice |
|---|---|
| Backend | Java 21, Spring Boot 3.4, Maven |
| Frontend | React, TypeScript, Vite |
| Data | PostgreSQL 16, Flyway (single forward-only stream) |
| Cache | Redis 7 |
| Messaging | RabbitMQ 3.13 |
| Auth | Local email/password + JWT (access + refresh); BCrypt; AES-GCM secret refs |
| API docs | springdoc OpenAPI + Swagger UI |
| Local runtime | Docker Compose |
| Eval isolation | Hardened Docker via `RuntimeIsolator` (local fallback for CI) |

## Architecture

Single Spring Boot deployable with modular package boundaries:

`identity` · `projects` · `benchmarks` · `agents` · `evaluation` · `execution` · `analytics` · `platform`

Tenant APIs use an explicit org base with membership checks on every request:

```text
/api/v1/orgs/{orgId}/...
```

Roles (org-scoped): `OWNER`, `ADMIN`, `DEVELOPER`, `VIEWER`.

## Quick start (Compose)

```bash
cp .env.example .env
docker compose up -d --build
```

| Service | URL |
|---|---|
| Frontend | http://localhost:3000 |
| Backend API | http://localhost:8080 |
| Health | http://localhost:8080/actuator/health |
| Swagger UI | http://localhost:8080/swagger-ui.html |
| OpenAPI JSON | http://localhost:8080/v3/api-docs |
| RabbitMQ management | http://localhost:15672 |

## Milestone 8 highlights

| Area | Behavior |
|---|---|
| Overview | `/analytics/overview` — counts by state, avg score, tokens/cost, recent evals, daily volume |
| Leaderboard | Org-private `/benchmark-versions/{id}/leaderboard` (best score per agent×task; not public) |
| Comparisons | `/analytics/comparisons` across identical benchmark versions with raw metric columns |
| Costs | `/analytics/costs` — tokens + estimated USD where adapters recorded them |
| History | Paginated `/analytics/evaluations` with search/state filters |
| Exports | Evaluation + leaderboard download as **JSON/CSV** (practical; no PDF stack) |
| Live status | Frontend polls in-flight evaluation detail every 3s |
| Ops | `/analytics/health` — actuator summary + org workload counts |
| UI | Dark/light themes; accessible loading/empty/error states on dashboard panels |
| Schema | Flyway `V8` analytics indexes only (no materialized metric tables) |

**Explicit non-claim:** M8 is analytics/reporting polish — **not** full production readiness (M9). Docker-only isolation remains unsuitable for arbitrary untrusted public workloads.

**Scoring (default-v1 weights)**

| Metric | Weight |
|---|---|
| `target_failure_resolution` | 30% |
| `regression` | 25% |
| `hidden` (post-only pass rate; never labeled F2P) | 20% |
| `code_quality` | 10% (real when tools run; else stub=0) |
| `edge_case` | 10% |
| `efficiency` | 5% |

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

## Flyway

Single migration stream under `backend/src/main/resources/db/migration`.

- `V1`–`V6` — platform through scoring engine
- `V7__security_quality.sql` — artifact cleanup audit + M7 marker

Never edit applied migrations; forward-only only.

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
