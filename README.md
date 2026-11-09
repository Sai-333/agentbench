# AgentBench

Multi-tenant platform for evaluating coding agents against versioned benchmarks with visible and hidden tests.

**Current milestone:** M4 — evaluation orchestration (durable state machine, RabbitMQ outbox/worker, baseline + patch validation).

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

## Architecture

Single Spring Boot deployable with modular package boundaries:

`identity` · `projects` · `benchmarks` · `agents` · `evaluation` · `execution` · `analytics` · `platform`

Tenant APIs use an explicit org base with membership checks on every request:

```text
/api/v1/orgs/{orgId}/...
```

Roles (org-scoped): `OWNER`, `ADMIN`, `DEVELOPER`, `VIEWER`.

Workspaces as a separate entity are **deferred** (not in the approved database design); organizations are the tenant boundary.

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

## Milestone 4 API surface

| Area | Paths |
|---|---|
| Evaluations | `POST/GET /api/v1/orgs/{orgId}/evaluations`, `GET .../{id}`, `POST .../{id}/cancel`, `GET .../{id}/transitions` |
| Agents (stub) | `POST/GET /api/v1/orgs/{orgId}/agents` |
| Benchmarks | retained from M3 (versioned tasks, visible/hidden suites) |
| Auth / orgs / projects | retained from M2 |

**Evaluation lifecycle (PRD states):** `QUEUED` → `PREPARING_REPO` → `RUNNING_BASELINE` → `INVOKING_AGENT` → `COLLECTING_PATCH` → `VALIDATING_PATCH` → `APPLYING_PATCH` → `RUNNING_VISIBLE_TESTS` → `RUNNING_HIDDEN_TESTS` → `ANALYZING_QUALITY` → `SCORING` → `COMPLETED` (plus `FAILED` / `CANCELLED` / `TIMED_OUT`).

**Hidden confidentiality:** VIEWER (evaluator) responses omit hidden fixture content, storage URIs, entry commands, digests, and expected outcomes. Agent-facing `agent-context` is an allowlist DTO with no hidden fields. Published versions freeze an immutable JSON snapshot and reject further task/suite edits.

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
- `AGENTBENCH_GITHUB_TOKEN` (optional live repo validation)

## Flyway

Single migration stream under `backend/src/main/resources/db/migration`.

- `V1__platform_baseline.sql` — extensions + platform schema marker
- `V2__identity_projects.sql` — users, orgs, memberships, refresh tokens, secret refs, projects, repo config, snapshots
- `V3__benchmarks.sql` — benchmarks, versions, tasks, visible/hidden test suites
- `V4__evaluations.sql` — agents stub, evaluations, transitions, stage attempts, patches, test results, outbox

Never edit applied migrations; forward-only only.

## CI

GitHub Actions builds backend (`mvn verify`, including Testcontainers when Docker is available) and frontend (`npm ci && npm run build`).

**Docker 29 note:** Engine 29 defaults to a minimum API of 1.44 while Testcontainers 1.21.x still negotiates an older client API. Local/daemon workaround:

```bash
echo '{"min-api-version":"1.24"}' | sudo tee /etc/docker/daemon.json
# restart dockerd
```

## Security notes

- Never returns plaintext secrets, ciphertext, or raw tokens in API responses.
- Hidden suite fixtures are RBAC-gated; agent-facing projections never include them.
- Org isolation is enforced in the application/repository layer (Postgres RLS deferred).
- Extensible `AuthProvider` SPI; only local password provider in M2/M3 (OIDC later).

## License

TBD
