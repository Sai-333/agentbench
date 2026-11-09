# AgentBench

Multi-tenant platform for evaluating coding agents against versioned benchmarks with visible and hidden tests.

**Current milestone:** M7 — Security and code quality (hardened Docker isolation + Checkstyle/SpotBugs/PMD).

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

## Milestone 7 highlights

| Area | Behavior |
|---|---|
| Docker hardening | No privileged, no docker.sock in eval containers, non-root, `--network none` by default, CPU/mem/pids/time limits, `--rm`, read-only rootfs + tmpfs, optional image digest pin |
| Stages | Agent workspace never mounts hidden fixtures; `HIDDEN_TESTS` uses a separate clone + staged fixtures, then cleanup |
| Quality | Checkstyle + SpotBugs + PMD (XML or builtin heuristics); new vs pre-existing findings; Sonar stub only |
| Scoring | `code_quality` uses real normalized scores when tools produce results; otherwise `stub=true` raw=0 (no invented scores) |
| Secrets / artifacts | `SecretRedactor` on logs/reports; retention TTL + max log bytes; cleanup audit (`V7`) |
| Patches | Rejects traversal, absolute paths, `.git/hooks`, encoded `..` |

**Explicit non-claim:** Docker-only isolation is **not** production-safe for arbitrary untrusted workloads. gVisor/Firecracker is planned (ADR-004). See `docs/SECURITY_THREAT_MODEL.md`.

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
