-- Platform-scoped Selenium/CI regression runs (git repo, branch, SHA).
-- Single Flyway stream; forward-only; do not edit V1–V22.
--
-- These rows are not tenant data: there is no org_id and no RLS policy.
-- Access control is the application check that the caller is OWNER or ADMIN
-- of at least one organization. See docs/REGRESSION_RUNS.md.

CREATE TABLE platform.regression_runs (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    git_repo        TEXT NOT NULL,
    git_branch      TEXT NOT NULL,
    git_sha         TEXT NOT NULL,
    suite_name      TEXT NOT NULL,
    started_at      TIMESTAMPTZ NOT NULL,
    finished_at     TIMESTAMPTZ NOT NULL,
    passed_count    INTEGER NOT NULL,
    failed_count    INTEGER NOT NULL,
    skipped_count   INTEGER NOT NULL,
    ingested_by     UUID REFERENCES identity.users (id) ON DELETE SET NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT regression_runs_repo_len CHECK (char_length(git_repo) BETWEEN 1 AND 500),
    CONSTRAINT regression_runs_branch_len CHECK (char_length(git_branch) BETWEEN 1 AND 255),
    CONSTRAINT regression_runs_sha_len CHECK (char_length(git_sha) BETWEEN 7 AND 64),
    CONSTRAINT regression_runs_suite_len CHECK (char_length(suite_name) BETWEEN 1 AND 120),
    CONSTRAINT regression_runs_counts_nonneg CHECK (
        passed_count >= 0 AND failed_count >= 0 AND skipped_count >= 0
    ),
    CONSTRAINT regression_runs_time_order CHECK (finished_at >= started_at)
);

COMMENT ON TABLE platform.regression_runs IS
    'Platform-scoped regression ingest (not org-scoped, no RLS). Counts are derived from child cases at ingest, not from a report summary block.';

CREATE INDEX regression_runs_started_idx
    ON platform.regression_runs (started_at DESC, created_at DESC);

CREATE TABLE platform.regression_cases (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    run_id          UUID NOT NULL REFERENCES platform.regression_runs (id) ON DELETE CASCADE,
    name            TEXT NOT NULL,
    status          TEXT NOT NULL,
    duration_ms     BIGINT NOT NULL,
    message         TEXT,
    position        INTEGER NOT NULL,
    CONSTRAINT regression_cases_name_len CHECK (char_length(name) BETWEEN 1 AND 500),
    CONSTRAINT regression_cases_status_check CHECK (status IN ('passed', 'failed', 'skipped')),
    CONSTRAINT regression_cases_duration_nonneg CHECK (duration_ms >= 0),
    CONSTRAINT regression_cases_message_len CHECK (message IS NULL OR char_length(message) <= 2000),
    CONSTRAINT regression_cases_position_nonneg CHECK (position >= 0)
);

COMMENT ON TABLE platform.regression_cases IS
    'One row per ingested test case. status is passed, failed, or skipped. JUnit/pytest error is stored as failed.';

CREATE INDEX regression_cases_run_idx
    ON platform.regression_cases (run_id, position);

GRANT SELECT, INSERT, UPDATE, DELETE ON platform.regression_runs TO agentbench_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON platform.regression_cases TO agentbench_app;

INSERT INTO platform.schema_marker (migration_label)
VALUES ('regression_runs');
