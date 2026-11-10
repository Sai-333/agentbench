# WORM / append-only audit storage

**Status:** Landed (Flyway `V15__worm_audit_storage.sql`)  
**Local path:** PostgreSQL triggers + privilege revoke + per-org SHA-256 hash chain. Works in Compose / Testcontainers without AWS.

## What shipped

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

## Honest residual (not claimed)

- **Not AWS S3 Object Lock / Compliance mode.** No object-store WORM path is wired in this PR.
- **Not a legal hold / regulated WORM appliance.** A Postgres superuser (Compose `POSTGRES_USER` / Flyway role) can `DISABLE TRIGGER`, `TRUNCATE`, or drop the table.
- **Org hard-delete** that would cascade into append-only children will fail while deny-delete triggers are enabled — treat audit history as durable.
- **`agents.agent_invocations`** remains a mutable lifecycle table (INSERT then UPDATE on completion), not WORM.
- Hash chain detects payload tampering when verification runs; it does not by itself stop a superuser who also rewrites hashes.

## Layering S3 Object Lock later

Optional hardening for hostile multi-tenant / compliance:

1. Export `platform.worm_audit_events` (or a CDC stream) to an S3 prefix as immutable objects (`event_id` key).
2. Enable **S3 Object Lock** in **Compliance** mode on that bucket/prefix (governance mode is weaker).
3. Keep Postgres as the online query store; treat the Object Lock bucket as the off-box evidence store.
4. Do **not** claim Object Lock until the export job + bucket settings are actually deployed and tested.

MinIO can approximate Object Lock in some deployments; confirm your MinIO version/features before claiming parity with AWS Compliance mode.

## Ops notes

- Flyway continues to use the bootstrap superuser; the app JDBC user must stay `agentbench_app` (`NOSUPERUSER`, `NOBYPASSRLS`).
- See also `docs/POSTGRES_RLS.md` and `docs/SECURITY_THREAT_MODEL.md`.
