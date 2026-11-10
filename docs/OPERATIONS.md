# AgentBench — Operational runbook (high level)

**Status:** Milestone 9 snippets for demo/staging operators.

## Backup

| Component | What to back up | Notes |
|---|---|---|
| PostgreSQL | Logical dump (`pg_dump`) or volume snapshot of `postgres_data` | Includes Flyway history, orgs, evals, scores |
| Artifacts volume | `artifacts_data` / PVC | Local blobs + eval workspaces; retention sweep deletes by TTL |
| MinIO / S3 bucket | `minio_data` or external bucket | When `ARTIFACT_STORAGE=s3`; same retention days; workspaces stay on local volume |
| Redis | Optional AOF (`appendonly yes`) | Locks/cache — rebuildable |
| RabbitMQ | Definitions + durable queues | Commands are also mirrored in evaluation outbox |

Suggested Postgres dump (Compose):

```bash
docker compose exec -T postgres pg_dump -U agentbench agentbench > agentbench-$(date -u +%Y%m%d).sql
```

## Migrate / upgrade

1. **Never edit applied Flyway scripts.** Forward-only (`V1`…`Vn`).
2. Take a DB dump before upgrading the backend image.
3. Roll backend first (API publishes outbox); then worker (consumes). Or use rolling updates with mixed versions only when migrations are backward-compatible.
4. Apply `kubectl apply -k deploy/kubernetes/` (or Compose rebuild) after secrets/config reviewed.
5. Confirm `flyway_schema_history` and `/api/v1/info` milestone after cutover.

```bash
docker compose exec postgres psql -U agentbench -d agentbench \
  -c "SELECT version, description, success FROM flyway_schema_history ORDER BY installed_rank;"
```

## DLQ / retry exhaustion

- Stage processor retries **infrastructure** failures up to `agentbench.evaluation.max-stage-attempts` (default 5) via outbox re-enqueue.
- Unexpected listener exceptions use Spring AMQP listener retry (`max-attempts`, default 3) then **reject without requeue** → `evaluation.commands.dlq`.
- `EvaluationDlqListener` logs redacted poison messages; **does not auto-replay**.
- Manual requeue (RabbitMQ management UI or `rabbitmqadmin`) only after inspecting root cause.

## Cancel / timeout

- `POST .../evaluations/{id}/cancel` — immediate if `QUEUED`; otherwise sets `cancel_requested` and worker cancels between stages.
- `EvaluationTimeoutWatchdog` marks `TIMED_OUT` when `started_at` exceeds org `max_duration_sec` (default 1800s).

## Invalid baseline

- If `requireTargetFailAtBaseline` (default true) and no target FAIL is observed, evaluation ends `FAILED` / `BASELINE_NOT_REPRODUCIBLE` (no agent retry).

## Artifact retention / cleanup

| Knob | Default | Effect |
|---|---|---|
| `AGENTBENCH_ARTIFACT_RETENTION_ENABLED` | `true` | When false, sweeps record `SKIPPED` and delete nothing |
| `AGENTBENCH_ARTIFACT_RETENTION_DAYS` | `14` | Objects / workspace files older than this are deleted |
| `AGENTBENCH_ARTIFACT_MAX_LOG_BYTES` | `20 MiB` | Truncate log/report payloads before persist |
| `AGENTBENCH_ARTIFACT_MAX_OBJECT_BYTES` | `20 MiB` | `ArtifactStorage.store` rejects larger `contentLength` |
| `AGENTBENCH_ARTIFACT_SWEEP_MS` | `3600000` | Scheduled sweep interval |

- **local:** sweep walks `AGENTBENCH_ARTIFACTS_ROOT` (blobs + workspaces).
- **s3:** sweep lists/deletes aged objects in the configured bucket (optional key prefix) and separately TTL-sweeps local `workspaces/`.
- Each run inserts into `platform.artifact_cleanup_runs` (`deleted_files`, `freed_bytes`, `status`, `message`).

Manual trigger is the Spring bean `ArtifactRetentionService#sweep` (no public HTTP endpoint in v1).

## Secret rotation

1. Generate new `AGENTBENCH_JWT_SECRET` / AES key.
2. Deploy new secret; existing JWTs invalidate on JWT rotation (users re-login).
3. AES key rotation for `secret_refs` ciphertext is **not** automated in M9 — plan dual-key re-encrypt before rotating encryption keys in production.

## Health checks after deploy

```bash
curl -sf "$API/actuator/health/liveness"
curl -sf "$API/actuator/health/readiness"
curl -sf "$API/api/v1/info"
```
