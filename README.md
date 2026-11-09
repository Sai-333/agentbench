# AgentBench

Multi-tenant platform for evaluating coding agents against versioned benchmarks with visible and hidden tests.

**Current milestone:** M1 — modular monolith foundation (no product CRUD yet).

## Stack

| Layer | Choice |
|---|---|
| Backend | Java 21, Spring Boot 3.4, Maven |
| Frontend | React, TypeScript, Vite |
| Data | PostgreSQL 16, Flyway (single forward-only stream) |
| Cache | Redis 7 |
| Messaging | RabbitMQ 3.13 |
| API docs | springdoc OpenAPI + Swagger UI |
| Local runtime | Docker Compose |

## Architecture (M1)

Single Spring Boot deployable with modular package boundaries:

`identity` · `projects` · `benchmarks` · `agents` · `evaluation` · `execution` · `analytics` · `platform`

Future tenant APIs use an explicit org base:

```text
/api/v1/orgs/{orgId}/...
```

Milestone 1 exposes platform info, Actuator health/readiness, and Swagger UI only. Module packages are stubbed for later milestones.

`ArtifactStorage` is defined in `platform` with a local filesystem implementation (Compose volume). S3-compatible storage comes later.

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

Compose runs `backend` (API) and `worker` (same image, `worker` profile) as **non-root**, with **no privileged mode** and **no docker.sock** mounts.

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
npm run dev
```

Vite proxies `/api`, `/actuator`, and Swagger paths to `http://localhost:8080`.

## Configuration

Environment variables are documented in `.env.example`. Do not commit real secrets.

Key Spring settings (Compose-injected):

- `SPRING_DATASOURCE_*`
- `SPRING_DATA_REDIS_*`
- `SPRING_RABBITMQ_*`
- `AGENTBENCH_ARTIFACTS_ROOT`
- `LOGGING_STRUCTURED_FORMAT_CONSOLE=logstash`

## Flyway

Single migration stream under `backend/src/main/resources/db/migration`.

`V1__platform_baseline.sql` enables `pgcrypto` + `citext`, creates the `platform` schema, and inserts a schema marker. Product entity tables start in Milestone 2+.

## CI

GitHub Actions (`.github/workflows/ci.yml`) builds the backend (`mvn verify`) and frontend (`npm ci && npm run build`) on pushes and pull requests to `main`.

## Security notes (M1)

- Actuator, OpenAPI, and `/api/v1/info` are open; JWT + org membership arrive in M2.
- Evaluation containers and Docker socket access are intentionally absent in M1.
- Isolation policy for later milestones: no privileged containers, no docker.sock in workloads, non-root app services.

## License

TBD
