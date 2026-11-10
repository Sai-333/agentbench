-- Defense-in-depth: PostgreSQL Row-Level Security on tenant-scoped tables.
-- Single Flyway stream; forward-only; do not edit V1–V11.
--
-- Application-layer org filters remain the primary isolation control.
-- RLS blocks cross-org reads/writes when a buggy query omits org_id, provided
-- the runtime DB role is NOT a superuser and does NOT have BYPASSRLS.
--
-- Session GUCs (set by app infrastructure on each connection/statement):
--   app.current_org_id  — UUID string of the active tenant
--   app.rls_bypass      — 'on' for controlled worker/admin cross-tenant scans

CREATE SCHEMA IF NOT EXISTS app;

COMMENT ON SCHEMA app IS 'Session helpers for tenant RLS (current_org_id / rls_bypass GUCs).';

CREATE OR REPLACE FUNCTION app.current_org_id()
RETURNS UUID
LANGUAGE sql
STABLE
AS $$
    SELECT NULLIF(current_setting('app.current_org_id', true), '')::uuid;
$$;

CREATE OR REPLACE FUNCTION app.rls_bypass()
RETURNS BOOLEAN
LANGUAGE sql
STABLE
AS $$
    SELECT COALESCE(current_setting('app.rls_bypass', true), 'off') = 'on';
$$;

CREATE OR REPLACE FUNCTION app.tenant_ok(row_org_id UUID)
RETURNS BOOLEAN
LANGUAGE sql
STABLE
AS $$
    SELECT app.rls_bypass()
        OR (row_org_id IS NOT NULL AND row_org_id = app.current_org_id());
$$;

COMMENT ON FUNCTION app.current_org_id() IS 'Reads SET app.current_org_id (empty → NULL).';
COMMENT ON FUNCTION app.rls_bypass() IS 'True when SET app.rls_bypass = on (workers / controlled admin).';
COMMENT ON FUNCTION app.tenant_ok(UUID) IS 'RLS predicate: bypass OR row org matches session org.';

-- ---------------------------------------------------------------------------
-- Non-superuser application role (RLS is a no-op for superusers even with FORCE).
-- Flyway continues to use the bootstrap superuser; the app JDBC user should be
-- agentbench_app (see application.yml / OPERATIONS.md).
-- ---------------------------------------------------------------------------
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'agentbench_app') THEN
        CREATE ROLE agentbench_app LOGIN PASSWORD 'agentbench' NOSUPERUSER NOBYPASSRLS NOCREATEDB NOCREATEROLE;
    END IF;
END
$$;

GRANT USAGE ON SCHEMA app TO agentbench_app;
GRANT USAGE ON SCHEMA platform TO agentbench_app;
GRANT USAGE ON SCHEMA identity TO agentbench_app;
GRANT USAGE ON SCHEMA projects TO agentbench_app;
GRANT USAGE ON SCHEMA benchmarks TO agentbench_app;
GRANT USAGE ON SCHEMA agents TO agentbench_app;
GRANT USAGE ON SCHEMA evaluations TO agentbench_app;
GRANT USAGE ON SCHEMA analytics TO agentbench_app;

GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA platform TO agentbench_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA identity TO agentbench_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA projects TO agentbench_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA benchmarks TO agentbench_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA agents TO agentbench_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA evaluations TO agentbench_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA analytics TO agentbench_app;

GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA platform TO agentbench_app;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA identity TO agentbench_app;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA projects TO agentbench_app;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA benchmarks TO agentbench_app;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA agents TO agentbench_app;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA evaluations TO agentbench_app;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA analytics TO agentbench_app;

GRANT EXECUTE ON FUNCTION app.current_org_id() TO agentbench_app;
GRANT EXECUTE ON FUNCTION app.rls_bypass() TO agentbench_app;
GRANT EXECUTE ON FUNCTION app.tenant_ok(UUID) TO agentbench_app;

ALTER DEFAULT PRIVILEGES IN SCHEMA platform GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO agentbench_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA identity GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO agentbench_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA projects GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO agentbench_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA benchmarks GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO agentbench_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA agents GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO agentbench_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA evaluations GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO agentbench_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA analytics GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO agentbench_app;

ALTER DEFAULT PRIVILEGES IN SCHEMA platform GRANT USAGE, SELECT ON SEQUENCES TO agentbench_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA identity GRANT USAGE, SELECT ON SEQUENCES TO agentbench_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA projects GRANT USAGE, SELECT ON SEQUENCES TO agentbench_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA benchmarks GRANT USAGE, SELECT ON SEQUENCES TO agentbench_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA agents GRANT USAGE, SELECT ON SEQUENCES TO agentbench_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA evaluations GRANT USAGE, SELECT ON SEQUENCES TO agentbench_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA analytics GRANT USAGE, SELECT ON SEQUENCES TO agentbench_app;

-- ---------------------------------------------------------------------------
-- Enable FORCE RLS + tenant policies on org_id tables.
-- Identity users/orgs/memberships/tokens/OIDC state stay without RLS so auth
-- and cross-org membership listing keep working without a tenant GUC.
-- Global scoring_profiles and platform.* markers are not tenant-scoped.
-- ---------------------------------------------------------------------------

-- Helper: enable + force + create standard policy for a table with org_id column.
-- (Expanded explicitly for Flyway clarity / reviewability.)

-- identity.secret_refs
ALTER TABLE identity.secret_refs ENABLE ROW LEVEL SECURITY;
ALTER TABLE identity.secret_refs FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation ON identity.secret_refs;
CREATE POLICY tenant_isolation ON identity.secret_refs
    FOR ALL
    USING (app.tenant_ok(org_id))
    WITH CHECK (app.tenant_ok(org_id));

-- projects.*
ALTER TABLE projects.projects ENABLE ROW LEVEL SECURITY;
ALTER TABLE projects.projects FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation ON projects.projects;
CREATE POLICY tenant_isolation ON projects.projects
    FOR ALL
    USING (app.tenant_ok(org_id))
    WITH CHECK (app.tenant_ok(org_id));

ALTER TABLE projects.project_repositories ENABLE ROW LEVEL SECURITY;
ALTER TABLE projects.project_repositories FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation ON projects.project_repositories;
CREATE POLICY tenant_isolation ON projects.project_repositories
    FOR ALL
    USING (app.tenant_ok(org_id))
    WITH CHECK (app.tenant_ok(org_id));

ALTER TABLE projects.repo_snapshots ENABLE ROW LEVEL SECURITY;
ALTER TABLE projects.repo_snapshots FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation ON projects.repo_snapshots;
CREATE POLICY tenant_isolation ON projects.repo_snapshots
    FOR ALL
    USING (app.tenant_ok(org_id))
    WITH CHECK (app.tenant_ok(org_id));

-- benchmarks.*
ALTER TABLE benchmarks.benchmarks ENABLE ROW LEVEL SECURITY;
ALTER TABLE benchmarks.benchmarks FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation ON benchmarks.benchmarks;
CREATE POLICY tenant_isolation ON benchmarks.benchmarks
    FOR ALL
    USING (app.tenant_ok(org_id))
    WITH CHECK (app.tenant_ok(org_id));

ALTER TABLE benchmarks.benchmark_versions ENABLE ROW LEVEL SECURITY;
ALTER TABLE benchmarks.benchmark_versions FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation ON benchmarks.benchmark_versions;
CREATE POLICY tenant_isolation ON benchmarks.benchmark_versions
    FOR ALL
    USING (app.tenant_ok(org_id))
    WITH CHECK (app.tenant_ok(org_id));

ALTER TABLE benchmarks.tasks ENABLE ROW LEVEL SECURITY;
ALTER TABLE benchmarks.tasks FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation ON benchmarks.tasks;
CREATE POLICY tenant_isolation ON benchmarks.tasks
    FOR ALL
    USING (app.tenant_ok(org_id))
    WITH CHECK (app.tenant_ok(org_id));

ALTER TABLE benchmarks.test_suites ENABLE ROW LEVEL SECURITY;
ALTER TABLE benchmarks.test_suites FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation ON benchmarks.test_suites;
CREATE POLICY tenant_isolation ON benchmarks.test_suites
    FOR ALL
    USING (app.tenant_ok(org_id))
    WITH CHECK (app.tenant_ok(org_id));

-- agents.*
ALTER TABLE agents.agents ENABLE ROW LEVEL SECURITY;
ALTER TABLE agents.agents FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation ON agents.agents;
CREATE POLICY tenant_isolation ON agents.agents
    FOR ALL
    USING (app.tenant_ok(org_id))
    WITH CHECK (app.tenant_ok(org_id));

ALTER TABLE agents.agent_invocations ENABLE ROW LEVEL SECURITY;
ALTER TABLE agents.agent_invocations FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation ON agents.agent_invocations;
CREATE POLICY tenant_isolation ON agents.agent_invocations
    FOR ALL
    USING (app.tenant_ok(org_id))
    WITH CHECK (app.tenant_ok(org_id));

-- evaluations.* (tenant-scoped)
ALTER TABLE evaluations.org_limits ENABLE ROW LEVEL SECURITY;
ALTER TABLE evaluations.org_limits FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation ON evaluations.org_limits;
CREATE POLICY tenant_isolation ON evaluations.org_limits
    FOR ALL
    USING (app.tenant_ok(org_id))
    WITH CHECK (app.tenant_ok(org_id));

ALTER TABLE evaluations.evaluations ENABLE ROW LEVEL SECURITY;
ALTER TABLE evaluations.evaluations FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation ON evaluations.evaluations;
CREATE POLICY tenant_isolation ON evaluations.evaluations
    FOR ALL
    USING (app.tenant_ok(org_id))
    WITH CHECK (app.tenant_ok(org_id));

ALTER TABLE evaluations.evaluation_state_transitions ENABLE ROW LEVEL SECURITY;
ALTER TABLE evaluations.evaluation_state_transitions FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation ON evaluations.evaluation_state_transitions;
CREATE POLICY tenant_isolation ON evaluations.evaluation_state_transitions
    FOR ALL
    USING (app.tenant_ok(org_id))
    WITH CHECK (app.tenant_ok(org_id));

ALTER TABLE evaluations.evaluation_stage_attempts ENABLE ROW LEVEL SECURITY;
ALTER TABLE evaluations.evaluation_stage_attempts FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation ON evaluations.evaluation_stage_attempts;
CREATE POLICY tenant_isolation ON evaluations.evaluation_stage_attempts
    FOR ALL
    USING (app.tenant_ok(org_id))
    WITH CHECK (app.tenant_ok(org_id));

ALTER TABLE evaluations.patch_artifacts ENABLE ROW LEVEL SECURITY;
ALTER TABLE evaluations.patch_artifacts FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation ON evaluations.patch_artifacts;
CREATE POLICY tenant_isolation ON evaluations.patch_artifacts
    FOR ALL
    USING (app.tenant_ok(org_id))
    WITH CHECK (app.tenant_ok(org_id));

ALTER TABLE evaluations.test_runs ENABLE ROW LEVEL SECURITY;
ALTER TABLE evaluations.test_runs FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation ON evaluations.test_runs;
CREATE POLICY tenant_isolation ON evaluations.test_runs
    FOR ALL
    USING (app.tenant_ok(org_id))
    WITH CHECK (app.tenant_ok(org_id));

ALTER TABLE evaluations.test_case_results ENABLE ROW LEVEL SECURITY;
ALTER TABLE evaluations.test_case_results FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation ON evaluations.test_case_results;
CREATE POLICY tenant_isolation ON evaluations.test_case_results
    FOR ALL
    USING (app.tenant_ok(org_id))
    WITH CHECK (app.tenant_ok(org_id));

ALTER TABLE evaluations.quality_reports ENABLE ROW LEVEL SECURITY;
ALTER TABLE evaluations.quality_reports FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation ON evaluations.quality_reports;
CREATE POLICY tenant_isolation ON evaluations.quality_reports
    FOR ALL
    USING (app.tenant_ok(org_id))
    WITH CHECK (app.tenant_ok(org_id));

ALTER TABLE evaluations.evaluation_scores ENABLE ROW LEVEL SECURITY;
ALTER TABLE evaluations.evaluation_scores FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation ON evaluations.evaluation_scores;
CREATE POLICY tenant_isolation ON evaluations.evaluation_scores
    FOR ALL
    USING (app.tenant_ok(org_id))
    WITH CHECK (app.tenant_ok(org_id));

-- score_metrics has no org_id; scope via parent evaluation_scores.
ALTER TABLE evaluations.score_metrics ENABLE ROW LEVEL SECURITY;
ALTER TABLE evaluations.score_metrics FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation ON evaluations.score_metrics;
CREATE POLICY tenant_isolation ON evaluations.score_metrics
    FOR ALL
    USING (
        app.rls_bypass()
        OR EXISTS (
            SELECT 1
            FROM evaluations.evaluation_scores s
            WHERE s.id = evaluation_score_id
              AND app.tenant_ok(s.org_id)
        )
    )
    WITH CHECK (
        app.rls_bypass()
        OR EXISTS (
            SELECT 1
            FROM evaluations.evaluation_scores s
            WHERE s.id = evaluation_score_id
              AND app.tenant_ok(s.org_id)
        )
    );

ALTER TABLE evaluations.flaky_detection_attempts ENABLE ROW LEVEL SECURITY;
ALTER TABLE evaluations.flaky_detection_attempts FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation ON evaluations.flaky_detection_attempts;
CREATE POLICY tenant_isolation ON evaluations.flaky_detection_attempts
    FOR ALL
    USING (app.tenant_ok(org_id))
    WITH CHECK (app.tenant_ok(org_id));

ALTER TABLE evaluations.outbox_events ENABLE ROW LEVEL SECURITY;
ALTER TABLE evaluations.outbox_events FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation ON evaluations.outbox_events;
CREATE POLICY tenant_isolation ON evaluations.outbox_events
    FOR ALL
    USING (app.tenant_ok(org_id))
    WITH CHECK (app.tenant_ok(org_id));

ALTER TABLE evaluations.processed_messages ENABLE ROW LEVEL SECURITY;
ALTER TABLE evaluations.processed_messages FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation ON evaluations.processed_messages;
CREATE POLICY tenant_isolation ON evaluations.processed_messages
    FOR ALL
    USING (app.tenant_ok(org_id))
    WITH CHECK (app.tenant_ok(org_id));

-- analytics.leaderboard_publications
ALTER TABLE analytics.leaderboard_publications ENABLE ROW LEVEL SECURITY;
ALTER TABLE analytics.leaderboard_publications FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation ON analytics.leaderboard_publications;
CREATE POLICY tenant_isolation ON analytics.leaderboard_publications
    FOR ALL
    USING (app.tenant_ok(org_id))
    WITH CHECK (app.tenant_ok(org_id));

-- Anonymous public leaderboard lookup by active slug (SELECT only).
DROP POLICY IF EXISTS public_active_slug_select ON analytics.leaderboard_publications;
CREATE POLICY public_active_slug_select ON analytics.leaderboard_publications
    FOR SELECT
    USING (unpublished_at IS NULL);

INSERT INTO platform.schema_marker (migration_label)
VALUES ('postgres_rls');
