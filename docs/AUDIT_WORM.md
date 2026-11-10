# WORM / append-only audit storage

**Status:** Postgres path landed (Flyway `V15__worm_audit_storage.sql`). Optional S3 Object Lock layer landed (Flyway `V22__worm_audit_s3_export_state.sql` + `agentbench.artifacts.s3.object-lock`).  
**Local path:** PostgreSQL triggers + privilege revoke + per-org SHA-256 hash chain. Works in Compose / Testcontainers without AWS.  
**Object Lock path:** Opt-in; default off. Requires `ARTIFACT_STORAGE=s3` and a bucket created with Object Lock enabled.

## What shipped (Postgres — always on)

| Control | Where |
|---|---|
| Deny `UPDATE` / `DELETE` | `platform.worm_deny_mutation()` trigger on `platform.worm_audit_events` and `evaluations.evaluation_state_transitions` |
| App role cannot rewrite | `agentbench_app` has `SELECT, INSERT` only on those tables (`UPDATE`/`DELETE` revoked) |
| Hash chain | Each `worm_audit_events` row stores `prev_hash` + `entry_hash` (SHA-256 over canonical payload); tip locked with `pg_advisory_xact_lock` per org |
| Dual-write | `AFTER INSERT` on `evaluation_state_transitions` copies each transition into `worm_audit_events` (`event_type=evaluation.state_transition`) |
| Verification | `WormAuditService.verifyChain(orgId)` recomputes hashes via the same SQL functions |
| Tests | `WormAuditImmutabilityTest` — app UPDATE/DELETE blocked; chain detects superuser tamper after trigger disable |

## How to append

Prefer domain writers that already insert transitions (evaluation lifecycle). For other events:

```java
wormAuditService.append(orgId, "suite.read", actor, "benchmark_suite", suiteId, payloadJson);
```

Inserts must run with `app.current_org_id` set (HTTP filter / `RlsContext`) so FORCE RLS allows the row.

## Optional S3 Object Lock layer

Layers an **off-box** evidence store on top of Postgres. Does **not** replace Postgres WORM.

| Knob | Env | Default | Meaning |
|---|---|---|---|
| enabled | `AGENTBENCH_S3_OBJECT_LOCK_ENABLED` | `false` | Master switch |
| mode | `AGENTBENCH_S3_OBJECT_LOCK_MODE` | `COMPLIANCE` | `GOVERNANCE` or `COMPLIANCE` |
| retention-days | `AGENTBENCH_S3_OBJECT_LOCK_RETENTION_DAYS` | `2555` | Retain-until = now + days |
| apply-to-artifacts | `AGENTBENCH_S3_OBJECT_LOCK_ARTIFACTS` | `false` | Put artifacts with Object Lock; refuse overwrite/delete/sweep |
| apply-to-audit-export | `AGENTBENCH_S3_OBJECT_LOCK_AUDIT_EXPORT` | `true` | Export worm rows to S3 with Object Lock |
| require-bucket-object-lock | `AGENTBENCH_S3_OBJECT_LOCK_REQUIRE_BUCKET` | `true` | Fail-fast `GetObjectLockConfiguration` at startup |
| audit-bucket | `AGENTBENCH_S3_OBJECT_LOCK_AUDIT_BUCKET` | _(artifact bucket)_ | Optional dedicated audit bucket |
| audit-key-prefix | `AGENTBENCH_S3_OBJECT_LOCK_AUDIT_PREFIX` | `audit/worm/` | Key prefix for exported JSON |
| audit-export-interval-ms | `AGENTBENCH_S3_OBJECT_LOCK_AUDIT_EXPORT_MS` | `60000` | Export poll interval |
| audit-export-batch-size | `AGENTBENCH_S3_OBJECT_LOCK_AUDIT_BATCH` | `200` | Max rows per poll |

### Behavior when enabled

1. **Fail-fast config** — `ARTIFACT_STORAGE=s3`, valid mode, `retention-days > 0`, and at least one of apply-to-artifacts / apply-to-audit-export.
2. **Bucket probe** (when `require-bucket-object-lock=true`) — startup calls `GetObjectLockConfiguration` and refuses to boot unless Object Lock is `ENABLED`.
3. **Artifacts** (when `apply-to-artifacts=true`) — `PutObject` includes `objectLockMode` + `objectLockRetainUntilDate`; existing keys refused (`HeadObject`); `delete` / orphan sweep refused at the app layer while protection is on.
4. **Audit export** (when `apply-to-audit-export=true`) — `WormAuditS3ExportService` copies new `worm_audit_events` rows to `s3://{bucket}/{prefix}events/{id}.json` with retention headers. Watermark in `platform.worm_audit_s3_export_state` (mutable ops state — **not** WORM evidence). Worm table stays append-only (no UPDATE of export flags on locked rows).

### Enabling on AWS

1. Create a versioned bucket with Object Lock enabled at creation time.
2. Set `ARTIFACT_STORAGE=s3` and `AGENTBENCH_S3_*` credentials/endpoint/region.
3. Set `AGENTBENCH_S3_OBJECT_LOCK_ENABLED=true` (and mode/retention as needed).
4. Keep `require-bucket-object-lock=true` for an honest Compliance claim.

### Compose / MinIO honesty

- Default `docker compose --profile s3` creates a **normal** MinIO bucket (`mc mb` **without** `--with-lock`). Object Lock stays **off**.
- MinIO can support Object Lock on some builds when the bucket is created with Object Lock (`mc mb --with-lock` / `ObjectLockEnabledForBucket=true`). Feature parity with AWS **Compliance** mode is **not** asserted for the default Compose profile.
- Do **not** set `AGENTBENCH_S3_OBJECT_LOCK_ENABLED=true` against the default Compose MinIO bucket — startup fail-fast will reject it when `require-bucket-object-lock=true`.

### Tests (evidence for the optional path)

| Test | What it proves |
|---|---|
| `S3ObjectLockArtifactStorageTest` | Put carries COMPLIANCE/GOVERNANCE + retain-until; overwrite/delete/sweep refused |
| `S3ObjectLockSupportTest` / `S3ObjectLockValidatorTest` | Config fail-fast; bucket probe required when configured |
| `WormAuditS3ExportServiceTest` | Export puts JSON under audit prefix with Object Lock headers + watermark advance |

These are SDK/Mockito unit tests (no live AWS Compliance bucket in CI). Claiming a **deployed** Compliance-mode store still requires an Object Lock-enabled bucket in the target environment.

## Honest residual (still not claimed without ops evidence)

- **Postgres path alone is not AWS Object Lock.** Superuser can still `DISABLE TRIGGER` / `TRUNCATE` on the DB side.
- **Default Compose / Helm leave Object Lock off.** Enabling knobs without an Object Lock bucket is refused (or, if `require-bucket-object-lock=false`, explicitly **not** a Compliance claim).
- **MinIO ≠ certified AWS Compliance** unless you verify your MinIO version and bucket lock settings yourself.
- **Artifact TTL/GC** cannot delete Object-Locked artifact objects while `apply-to-artifacts=true`; metadata purge may still clear URIs while bytes remain until retention expires.
- **`agents.agent_invocations`** remains a mutable lifecycle table, not WORM.
- Hash chain detects payload tampering when verification runs; it does not by itself stop a superuser who also rewrites hashes.

## Ops notes

- Flyway continues to use the bootstrap superuser; the app JDBC user must stay `agentbench_app` (`NOSUPERUSER`, `NOBYPASSRLS`).
- Helm: `config.s3ObjectLock*` → ConfigMap (`AGENTBENCH_S3_OBJECT_LOCK_*`); default `"false"`.
- See also `docs/POSTGRES_RLS.md`, `docs/DEPLOYMENT.md`, and `docs/SECURITY_THREAT_MODEL.md`.
