# PostgreSQL Row-Level Security (defense-in-depth)

**Status:** Landed (Flyway `V12__row_level_security.sql`)  
**Primary isolation:** Application-layer membership checks + `org_id` repository filters (unchanged).  
**RLS role:** Blocks cross-tenant SELECT/UPDATE/DELETE when a buggy query omits `org_id`, **only** when the JDBC role is not a superuser and does not have `BYPASSRLS`.

## How it works

1. Tenant tables with `org_id` (projects, benchmarks, evaluations, agents, secret refs, artifact/eval metadata, leaderboard publications, …) have `ENABLE` + `FORCE ROW LEVEL SECURITY`.
2. Policies use `app.tenant_ok(org_id)` → true when:
   - `current_setting('app.rls_bypass') = 'on'`, or
   - `org_id = nullif(current_setting('app.current_org_id'), '')::uuid`
3. Infrastructure sets GUCs before each JDBC statement from thread-local context:
   - HTTP: `OrgRlsFilter` parses `/api/v1/orgs/{orgId}/...`
   - Workers: org from `EvaluationCommand`, or short-lived bypass for outbox/timeout scans
4. `evaluations.score_metrics` has no `org_id`; policy scopes via parent `evaluation_scores`.
5. Identity tables (`users`, `organizations`, `memberships`, tokens, OIDC state) are **not** RLS-scoped so auth and “list my orgs” keep working.
6. Active public leaderboard rows are readable by slug via a SELECT-only policy (`unpublished_at IS NULL`).

## Roles

| Role | Purpose |
|---|---|
| `agentbench` (Compose `POSTGRES_USER`) | Superuser / Flyway migrations. **Bypasses RLS** even with `FORCE`. |
| `agentbench_app` | Application + worker JDBC user. `NOSUPERUSER` `NOBYPASSRLS` — subject to FORCE RLS. |

Compose creates `agentbench_app` on first Postgres volume init (`deploy/postgres/init-app-role.sh`). Flyway V12 creates it if missing and grants table privileges.

```yaml
SPRING_DATASOURCE_USERNAME: agentbench_app
SPRING_FLYWAY_USER: agentbench
```

## Connection pool / session reset

- Do **not** rely on leftover session GUCs across pool reuse.
- The app wraps the pool so every statement reapplies `app.current_org_id` / `app.rls_bypass` from `RlsContext` (`SET LOCAL` inside a transaction, session-level when autocommit).
- Clearing `RlsContext` (filter `finally`, worker `finally`) yields empty org + bypass off → tenant queries see **zero** rows.

## Migrations

- Forward-only Flyway only; never edit applied scripts.
- Future migrations that touch tenant data while connected as a non-superuser must `SELECT set_config('app.rls_bypass', 'on', true)` (or run as the Flyway superuser — the default).
- After adding a new tenant table: `ENABLE` + `FORCE ROW LEVEL SECURITY` and a `tenant_isolation` policy in a new `Vn__…sql`.

## Admin / bypass

- Controlled bypass GUC: `app.rls_bypass=on` (workers: outbox drain, timeout sweep; slug uniqueness check).
- There is **no** separate `BYPASSRLS` login for the app. Ops DBA work should use the migration superuser (or a dedicated break-glass role with `BYPASSRLS` created outside the app — not shipped).
- Superuser JDBC (misconfiguration) silently disables RLS — treat that as a deploy defect.

## Verification

- Integration test: `PostgresRlsIsolationTest` (Testcontainers) proves cross-org SELECT/UPDATE/DELETE fail without an org filter under `agentbench_app`.
- App-layer isolation tests (`AuthOrgIsolationTest`, etc.) remain required.

## Residual risk

RLS does not replace RBAC, hidden-suite controls, or runtime isolation. It does not protect against a compromised superuser connection or an over-broad bypass window.
