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

## Postgres RLS (defense-in-depth)

See **[POSTGRES_RLS.md](./POSTGRES_RLS.md)** for full caveats. Short version:

| Concern | Guidance |
|---|---|
| App vs migrate user | JDBC = `agentbench_app` (FORCE RLS); Flyway = `POSTGRES_USER` / `SPRING_FLYWAY_USER` (superuser) |
| Pool sessions | GUC reapplied per statement from `RlsContext`; never leave org/bypass set across requests |
| New tenant tables | New Flyway version: `ENABLE`+`FORCE` RLS + `tenant_isolation` policy |
| Break-glass | Use migrate superuser; do not point the app pool at a superuser in prod |
| Existing volumes | If `agentbench_app` is missing, run V12 (or `deploy/postgres/init-app-role.sh` logic) before switching `SPRING_DATASOURCE_USERNAME` |

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

## Artifact retention / cleanup (TTL + GC)

Addresses disk exhaustion risk from large patches/logs (threat-model R9) alongside size ceilings.

| Knob | Default | Effect |
|---|---|---|
| `AGENTBENCH_ARTIFACT_RETENTION_ENABLED` | `true` | When false, sweeps record `SKIPPED` and delete nothing |
| `AGENTBENCH_ARTIFACT_RETENTION_DAYS` | `14` | TTL: blobs / workspace files / patch metadata older than this are purged |
| `AGENTBENCH_ARTIFACT_MAX_LOG_BYTES` | `20 MiB` | Truncate log/report payloads before persist |
| `AGENTBENCH_ARTIFACT_MAX_OBJECT_BYTES` | `20 MiB` | `ArtifactStorage.store` rejects larger `contentLength` |
| `AGENTBENCH_ARTIFACT_SWEEP_MS` | `3600000` | Scheduled GC interval (`fixedDelay`) |
| `AGENTBENCH_ARTIFACT_SECRET_SCAN_ENABLED` | `true` | Pattern scan on artifact persist (patches, fixtures, invocation outputs) |
| `AGENTBENCH_ARTIFACT_SECRET_SCAN_MODE` | `REDACT` | `REDACT` stores redacted bytes; `QUARANTINE` refuses persist on findings |

Each GC sweep (Spring `@Scheduled` on API + worker):

1. **DB metadata (RLS bypass):** select `evaluations.patch_artifacts` with `collected_at < cutoff` and `purged_at IS NULL` (batch ≤ 500); delete the blob via active `ArtifactStorage`; set `purged_at` and clear `storage_uri`. Clear terminal `agents.agent_invocations.patch_storage_uri` older than the cutoff.
2. **Orphan blobs:** age-sweep via `ArtifactStorage.sweepOlderThan` — **local** walks `AGENTBENCH_ARTIFACTS_ROOT`; **s3** lists/deletes aged objects (optional key prefix).
3. **Workspaces:** when `ARTIFACT_STORAGE=s3`, also TTL-sweeps local `workspaces/` under the artifacts root.
4. Audit row in `platform.artifact_cleanup_runs` (`deleted_files`, `freed_bytes`, `deleted_metadata`, `status`, `message`).

### Artifact secret scanning (R7)

Addresses secret exfiltration via patches / env dumps / logs persisted as artifacts. `ArtifactSecretScanner` runs before digest + store (and again inside the `ScanningArtifactStorage` wrapper on every `ArtifactStorage.store`):

- Built-in detectors: Bearer / `x-api-key` headers, OpenAI-style `sk-…`, JSON secret fields, AWS access keys, AWS secret assignments, PEM private keys, JWTs, GitHub / Slack tokens, env-style `password=` / `api_key=` dumps.
- Optional extra regexes via `agentbench.artifacts.secret-scan.additional-patterns` (config file / Helm values — not a full DLP rule engine).
- **REDACT** (default): persist redacted bytes; content digest matches stored content.
- **QUARANTINE**: refuse persist; throw `ArtifactSecretQuarantinedException` (message has pattern ids + counts only — never the secret).
- Audit rows in `platform.artifact_secret_findings` (Flyway V18): `storage_key`, `pattern_ids`, `finding_count`, `action` — **no matched substrings**.

**Not claimed:** full DLP / ML classifiers; scanning of binary non-text blobs; S3 Object Lock / bucket lifecycle; per-org TTL or per-org scan policy overrides.

Manual GC trigger is the Spring bean `ArtifactRetentionService#sweep` (no public HTTP endpoint in v1).

## Evaluation isolation mode

| Mode | When to use | Behavior if unavailable |
|---|---|---|
| `docker` (default) | Mac Compose, trusted demos | Falls back to `local` process isolator |
| `gvisor` | Linux Docker host with gVisor `runsc` registered | **Fail-fast** — clear error; set `AGENTBENCH_EXECUTION_MODE=docker` or `local` |
| `firecracker` | Linux host with KVM + Firecracker (+ documented wrapper) | **Fail-fast** — clear error; never claim a live microVM without detection |
| `local` | CI / unit tests without a daemon | Always available; **not** a security boundary |

```bash
# Linux worker with runsc installed and Docker runtime configured:
export AGENTBENCH_EXECUTION_MODE=gvisor
# Optional overrides:
# export AGENTBENCH_GVISOR_RUNTIME=runsc
# export AGENTBENCH_RUNSC_BINARY=runsc

# Linux+KVM worker with Firecracker (see scripts/firecracker/agentbench-fc-run.sh):
# ln -s "$(pwd)/scripts/firecracker/agentbench-fc-run.sh" /usr/local/bin/agentbench-fc-run
# export AGENTBENCH_FC_KERNEL=/path/to/vmlinux
# export AGENTBENCH_FC_ROOTFS=/path/to/rootfs.ext4
export AGENTBENCH_EXECUTION_MODE=firecracker
# Optional: AGENTBENCH_FIRECRACKER_USE_JAILER=true, AGENTBENCH_JAILER_BINARY=jailer
```

See [ADR-004](./ARCHITECTURE_DECISIONS/ADR-004-docker-first-isolation.md). Do not change the Mac Compose default away from Docker. Residual: Firecracker opt-in is not a certified multi-tenant SaaS claim.

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
