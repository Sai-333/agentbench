# AgentBench

Multi-tenant platform for evaluating coding agents against versioned benchmarks with visible and hidden tests.

**Current milestone:** M2 — authentication, organizations, RBAC, projects, and GitHub repo configuration.

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

## Milestone 2 API surface

| Area | Paths |
|---|---|
| Auth | `POST /api/v1/auth/register`, `/login`, `/refresh`, `/logout`; `GET /api/v1/me` |
| Orgs | `POST/GET /api/v1/orgs`, `GET /api/v1/orgs/{orgId}`, members CRUD |
| Secrets | `/api/v1/orgs/{orgId}/secrets` (metadata only in responses) |
| Projects | `/api/v1/orgs/{orgId}/projects` CRUD |
| Repo config | `/projects/{projectId}/repository` (+ `/validate`) |
| Snapshots | `/projects/{projectId}/snapshots`, `/snapshots/{snapshotId}` |

GitHub accessibility validation uses an org `GIT` secret or `AGENTBENCH_GITHUB_TOKEN`. Without a token, status is `SKIPPED` (clear message), not a hard failure.

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

Notable M2 variables:

- `AGENTBENCH_JWT_SECRET` (≥ 32 chars)
- `AGENTBENCH_SECRETS_ENCRYPTION_KEY` (base64 32-byte AES key)
- `AGENTBENCH_GITHUB_TOKEN` (optional live repo validation)

## Flyway

Single migration stream under `backend/src/main/resources/db/migration`.

- `V1__platform_baseline.sql` — extensions + platform schema marker
- `V2__identity_projects.sql` — users, orgs, memberships, refresh tokens, secret refs, projects, repo config, snapshots

Never edit applied migrations; forward-only only.

## CI

GitHub Actions builds backend (`mvn verify`, including Testcontainers auth/org isolation tests when Docker is available) and frontend (`npm ci && npm run build`).

## Security notes

- Never returns plaintext secrets, ciphertext, or raw tokens in API responses.
- Org isolation is enforced in the application/repository layer (Postgres RLS deferred).
- Extensible `AuthProvider` SPI; only local password provider in M2 (OIDC later).

## License

TBD
