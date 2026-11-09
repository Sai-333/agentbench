# AgentBench

Multi-tenant platform for evaluating coding agents against versioned benchmarks with visible and hidden tests.

**Current milestone:** M5 — AI agent integration (provider-independent SPI, OpenAI-compatible adapter, invocation audit).

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

Agent providers are selected via the `AgentProvider` SPI (no Spring AI / vendor SDK types in domain):

```text
FAKE · OPENAI_COMPATIBLE · HTTP · CODING_CLI (skeleton)
```

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

## Milestone 5 API surface

| Area | Paths |
|---|---|
| Agents | `POST/GET/PATCH/DELETE /api/v1/orgs/{orgId}/agents`, `POST .../{id}/test-connection` |
| Evaluations | retained from M4; detail includes `agentInvocations` (provider, model, tokens, cost, retries, status) |
| Secrets | org secret refs for API keys (metadata only in responses) |
| Benchmarks / auth / projects | retained from M2–M3 |

**Adapters**

- `FAKE` — deterministic CI/demo patches (no external API)
- `OPENAI_COMPATIBLE` — Chat Completions HTTP client; credentials via `secretRefId`
- `HTTP` — webhook-style agent endpoint expecting `{ "patch": "..." }`
- `CODING_CLI` — SPI + stub (fixture `config.patch` for demos; process isolation later)

**Hidden confidentiality:** agent prompts receive visible task context only. Secrets are never returned in API/logs; inline `apiKey` in agent config is rejected.

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
- `AGENTBENCH_AGENTS_MAX_RETRIES` / `AGENTBENCH_AGENTS_TIMEOUT_SEC`
- `AGENTBENCH_OPENAI_BASE_URL` (default `https://api.openai.com`)

## Flyway

Single migration stream under `backend/src/main/resources/db/migration`.

- `V1__platform_baseline.sql` — extensions + platform schema marker
- `V2__identity_projects.sql` — users, orgs, memberships, refresh tokens, secret refs, projects, repo config, snapshots
- `V3__benchmarks.sql` — benchmarks, versions, tasks, visible/hidden test suites
- `V4__evaluations.sql` — agents stub, evaluations, transitions, stage attempts, patches, test results, outbox
- `V5__agent_invocations.sql` — expanded adapter types + `agents.agent_invocations` audit

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
- Extensible `AuthProvider` SPI; only local password provider in M2–M5 (OIDC later).
- Agent API keys use encrypted `secret_refs`; never store keys in `config_json`.

## License

TBD
