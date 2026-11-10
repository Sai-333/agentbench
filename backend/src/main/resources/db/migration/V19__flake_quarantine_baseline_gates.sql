-- Flake quarantine + baseline confirmation metadata (R5).
-- Single Flyway stream; forward-only; do not edit V1–V18.
-- Quarantined cases/suites are excluded from unfair scoring penalties; baseline
-- confirmation is enforced in the worker when configured (repeat + stable gate).

CREATE TABLE evaluations.flake_quarantines (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id              UUID NOT NULL REFERENCES identity.organizations (id) ON DELETE CASCADE,
    suite_id            UUID REFERENCES benchmarks.test_suites (id) ON DELETE CASCADE,
    case_key            TEXT,
    reason              TEXT NOT NULL DEFAULT '',
    active              BOOLEAN NOT NULL DEFAULT TRUE,
    created_by_user_id  UUID REFERENCES identity.users (id) ON DELETE SET NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at          TIMESTAMPTZ,
    CONSTRAINT flake_quarantines_scope_check CHECK (
        suite_id IS NOT NULL OR (case_key IS NOT NULL AND btrim(case_key) <> '')
    ),
    CONSTRAINT flake_quarantines_case_key_len CHECK (
        case_key IS NULL OR char_length(btrim(case_key)) BETWEEN 1 AND 512
    ),
    CONSTRAINT flake_quarantines_reason_len CHECK (char_length(reason) <= 1000)
);

COMMENT ON TABLE evaluations.flake_quarantines IS
    'Org-scoped known-flake quarantine. Suite-level (case_key NULL) or case-level. Excluded from scoring transitions / denominators when active and not expired.';

COMMENT ON COLUMN evaluations.flake_quarantines.case_key IS
    'Null = quarantine entire suite (suite_id required). Non-null = quarantine that case key (suite_id optional org-wide match).';

-- One active row per (org, suite, case) scope. NULLS NOT DISTINCT so suite-level
-- and org-wide case keys stay unique under Postgres 15+.
CREATE UNIQUE INDEX flake_quarantines_active_scope_uidx
    ON evaluations.flake_quarantines (org_id, suite_id, case_key)
    NULLS NOT DISTINCT
    WHERE active = TRUE;

CREATE INDEX flake_quarantines_org_active_idx
    ON evaluations.flake_quarantines (org_id, active)
    WHERE active = TRUE;

CREATE INDEX flake_quarantines_suite_idx
    ON evaluations.flake_quarantines (suite_id)
    WHERE suite_id IS NOT NULL AND active = TRUE;

-- Persist whether a baseline passed the confirmation gate (nullable for older rows).
ALTER TABLE evaluations.test_runs
    ADD COLUMN IF NOT EXISTS baseline_confirmed BOOLEAN;

COMMENT ON COLUMN evaluations.test_runs.baseline_confirmed IS
    'BASELINE phase only: true when confirmation gate passed; false when failed closed; null when gate disabled or non-baseline phase.';

ALTER TABLE evaluations.flake_quarantines ENABLE ROW LEVEL SECURITY;
ALTER TABLE evaluations.flake_quarantines FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation ON evaluations.flake_quarantines;
CREATE POLICY tenant_isolation ON evaluations.flake_quarantines
    FOR ALL
    USING (app.tenant_ok(org_id))
    WITH CHECK (app.tenant_ok(org_id));

GRANT SELECT, INSERT, UPDATE, DELETE ON evaluations.flake_quarantines TO agentbench_app;

INSERT INTO platform.schema_marker (migration_label)
VALUES ('flake_quarantine_baseline_gates');
