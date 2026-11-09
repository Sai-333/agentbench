-- Milestone 3: benchmarks, versions, tasks, visible/hidden test suites.
-- Single Flyway stream; forward-only; do not edit V1/V2.

CREATE SCHEMA IF NOT EXISTS benchmarks;

COMMENT ON SCHEMA benchmarks IS 'Benchmark definitions, immutable versions, tasks, and test suites.';

-- ---------------------------------------------------------------------------
-- Benchmarks (org-scoped; optionally linked to a project)
-- ---------------------------------------------------------------------------
CREATE TABLE benchmarks.benchmarks (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id          UUID NOT NULL REFERENCES identity.organizations (id) ON DELETE CASCADE,
    project_id      UUID REFERENCES projects.projects (id) ON DELETE SET NULL,
    name            TEXT NOT NULL,
    description     TEXT NOT NULL DEFAULT '',
    created_by      UUID NOT NULL REFERENCES identity.users (id),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT benchmarks_org_name_unique UNIQUE (org_id, name)
);

CREATE INDEX benchmarks_org_id_idx ON benchmarks.benchmarks (org_id, created_at DESC);
CREATE INDEX benchmarks_project_id_idx ON benchmarks.benchmarks (org_id, project_id);

-- ---------------------------------------------------------------------------
-- Benchmark versions (DRAFT editable; PUBLISHED immutable snapshot)
-- ---------------------------------------------------------------------------
CREATE TABLE benchmarks.benchmark_versions (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    benchmark_id            UUID NOT NULL REFERENCES benchmarks.benchmarks (id) ON DELETE CASCADE,
    org_id                  UUID NOT NULL REFERENCES identity.organizations (id) ON DELETE CASCADE,
    version                 TEXT NOT NULL,
    status                  TEXT NOT NULL DEFAULT 'DRAFT',
    changelog               TEXT NOT NULL DEFAULT '',
    scoring_profile_key     TEXT NOT NULL DEFAULT 'default-v1',
    published_at            TIMESTAMPTZ,
    published_by            UUID REFERENCES identity.users (id),
    immutable_snapshot      JSONB,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT benchmark_versions_unique UNIQUE (benchmark_id, version),
    CONSTRAINT benchmark_versions_status_check CHECK (status IN ('DRAFT', 'PUBLISHED', 'RETIRED')),
    CONSTRAINT benchmark_versions_publish_consistency CHECK (
        (status = 'DRAFT' AND published_at IS NULL AND immutable_snapshot IS NULL)
        OR (status IN ('PUBLISHED', 'RETIRED') AND published_at IS NOT NULL AND immutable_snapshot IS NOT NULL)
    )
);

CREATE INDEX benchmark_versions_org_id_idx
    ON benchmarks.benchmark_versions (org_id, benchmark_id, created_at DESC);
CREATE INDEX benchmark_versions_status_idx
    ON benchmarks.benchmark_versions (org_id, status);

-- ---------------------------------------------------------------------------
-- Tasks within a version
-- ---------------------------------------------------------------------------
CREATE TABLE benchmarks.tasks (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    benchmark_version_id    UUID NOT NULL REFERENCES benchmarks.benchmark_versions (id) ON DELETE CASCADE,
    org_id                  UUID NOT NULL REFERENCES identity.organizations (id) ON DELETE CASCADE,
    slug                    TEXT NOT NULL,
    title                   TEXT NOT NULL,
    prompt                  TEXT NOT NULL,
    acceptance_criteria     TEXT NOT NULL DEFAULT '',
    category                TEXT,
    difficulty              TEXT,
    language_tags           JSONB NOT NULL DEFAULT '[]'::jsonb,
    repo_snapshot_id        UUID REFERENCES projects.repo_snapshots (id) ON DELETE SET NULL,
    baseline_commit_sha     CHAR(40),
    reference_patch         TEXT NOT NULL DEFAULT '',
    expected_behavior       TEXT NOT NULL DEFAULT '',
    timeout_sec             INT NOT NULL DEFAULT 1800,
    memory_mb               INT NOT NULL DEFAULT 2048,
    cpu_limit               NUMERIC(6, 2),
    max_patch_bytes         INT NOT NULL DEFAULT 5242880,
    eval_config_json        JSONB NOT NULL DEFAULT '{}'::jsonb,
    ordinal                 INT NOT NULL DEFAULT 0,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT tasks_version_slug_unique UNIQUE (benchmark_version_id, slug),
    CONSTRAINT tasks_difficulty_check CHECK (
        difficulty IS NULL OR difficulty IN ('EASY', 'MEDIUM', 'HARD', 'EXPERT')
    ),
    CONSTRAINT tasks_timeout_check CHECK (timeout_sec > 0 AND timeout_sec <= 86400),
    CONSTRAINT tasks_memory_check CHECK (memory_mb > 0 AND memory_mb <= 65536),
    CONSTRAINT tasks_max_patch_check CHECK (max_patch_bytes > 0),
    CONSTRAINT tasks_baseline_sha_check CHECK (
        baseline_commit_sha IS NULL OR baseline_commit_sha ~ '^[0-9a-fA-F]{40}$'
    )
);

CREATE INDEX tasks_org_version_idx
    ON benchmarks.tasks (org_id, benchmark_version_id, ordinal, slug);

-- ---------------------------------------------------------------------------
-- Test suites: one VISIBLE and one HIDDEN per task (optional until authored)
-- Hidden fixture payloads must never appear on agent-facing surfaces.
-- ---------------------------------------------------------------------------
CREATE TABLE benchmarks.test_suites (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    task_id                 UUID NOT NULL REFERENCES benchmarks.tasks (id) ON DELETE CASCADE,
    org_id                  UUID NOT NULL REFERENCES identity.organizations (id) ON DELETE CASCADE,
    visibility              TEXT NOT NULL,
    framework               TEXT NOT NULL,
    storage_uri             TEXT NOT NULL DEFAULT '',
    entry_command           TEXT NOT NULL DEFAULT '',
    content_digest          TEXT NOT NULL DEFAULT '',
    fixture_content         TEXT NOT NULL DEFAULT '',
    expected_outcomes_json  JSONB NOT NULL DEFAULT '{}'::jsonb,
    case_count              INT,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT test_suites_task_visibility_unique UNIQUE (task_id, visibility),
    CONSTRAINT test_suites_visibility_check CHECK (visibility IN ('VISIBLE', 'HIDDEN')),
    CONSTRAINT test_suites_case_count_check CHECK (case_count IS NULL OR case_count >= 0)
);

CREATE INDEX test_suites_org_task_idx ON benchmarks.test_suites (org_id, task_id);
CREATE INDEX test_suites_hidden_idx ON benchmarks.test_suites (org_id, visibility)
    WHERE visibility = 'HIDDEN';

INSERT INTO platform.schema_marker (migration_label)
VALUES ('M3_benchmarks');
