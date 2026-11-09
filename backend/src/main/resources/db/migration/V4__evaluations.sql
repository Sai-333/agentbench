-- Milestone 4: evaluation orchestration — agents stub, evaluations, outbox, results.
-- Single Flyway stream; forward-only; do not edit V1–V3.

CREATE SCHEMA IF NOT EXISTS agents;
CREATE SCHEMA IF NOT EXISTS evaluations;

COMMENT ON SCHEMA agents IS 'Org-scoped agent registry (M4 stub; full adapters in M5).';
COMMENT ON SCHEMA evaluations IS 'Evaluation lifecycle, audit trail, patches, and test results.';

-- ---------------------------------------------------------------------------
-- Agents (minimal registry so evaluations can reference an agent)
-- ---------------------------------------------------------------------------
CREATE TABLE agents.agents (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id          UUID NOT NULL REFERENCES identity.organizations (id) ON DELETE CASCADE,
    name            TEXT NOT NULL,
    adapter_type    TEXT NOT NULL,
    secret_ref_id   UUID REFERENCES identity.secret_refs (id) ON DELETE SET NULL,
    config_json     JSONB NOT NULL DEFAULT '{}'::jsonb,
    enabled         BOOLEAN NOT NULL DEFAULT TRUE,
    created_by      UUID NOT NULL REFERENCES identity.users (id),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT agents_org_name_unique UNIQUE (org_id, name),
    CONSTRAINT agents_adapter_type_check CHECK (adapter_type IN ('FAKE', 'HTTP', 'SPRING_AI'))
);

CREATE INDEX agents_org_id_idx ON agents.agents (org_id, enabled, created_at DESC);

-- ---------------------------------------------------------------------------
-- Org evaluation limits (overrides preferences defaults when set)
-- ---------------------------------------------------------------------------
CREATE TABLE evaluations.org_limits (
    org_id                      UUID PRIMARY KEY REFERENCES identity.organizations (id) ON DELETE CASCADE,
    max_concurrent_evaluations  INT NOT NULL DEFAULT 2,
    max_queued_evaluations      INT NOT NULL DEFAULT 20,
    max_concurrent_agent_calls  INT NOT NULL DEFAULT 2,
    max_duration_sec            INT NOT NULL DEFAULT 1800,
    max_patch_bytes             INT NOT NULL DEFAULT 5242880,
    max_log_artifact_bytes      INT NOT NULL DEFAULT 20971520,
    updated_at                  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT org_limits_concurrent_check CHECK (max_concurrent_evaluations > 0 AND max_concurrent_evaluations <= 100),
    CONSTRAINT org_limits_queued_check CHECK (max_queued_evaluations > 0 AND max_queued_evaluations <= 10000),
    CONSTRAINT org_limits_agent_calls_check CHECK (max_concurrent_agent_calls > 0 AND max_concurrent_agent_calls <= 100),
    CONSTRAINT org_limits_duration_check CHECK (max_duration_sec > 0 AND max_duration_sec <= 86400),
    CONSTRAINT org_limits_patch_check CHECK (max_patch_bytes > 0),
    CONSTRAINT org_limits_log_check CHECK (max_log_artifact_bytes > 0)
);

-- ---------------------------------------------------------------------------
-- Evaluations
-- ---------------------------------------------------------------------------
CREATE TABLE evaluations.evaluations (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id                  UUID NOT NULL REFERENCES identity.organizations (id) ON DELETE CASCADE,
    benchmark_version_id    UUID NOT NULL REFERENCES benchmarks.benchmark_versions (id) ON DELETE RESTRICT,
    task_id                 UUID NOT NULL REFERENCES benchmarks.tasks (id) ON DELETE RESTRICT,
    agent_id                UUID NOT NULL REFERENCES agents.agents (id) ON DELETE RESTRICT,
    state                   TEXT NOT NULL,
    attempt                 INT NOT NULL DEFAULT 1,
    failure_class           TEXT,
    error_code              TEXT,
    error_message           TEXT,
    idempotency_key         TEXT,
    snapshot_json           JSONB NOT NULL,
    workspace_uri           TEXT,
    cancel_requested        BOOLEAN NOT NULL DEFAULT FALSE,
    created_by              UUID NOT NULL REFERENCES identity.users (id),
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    started_at              TIMESTAMPTZ,
    completed_at            TIMESTAMPTZ,
    CONSTRAINT evaluations_state_check CHECK (state IN (
        'QUEUED',
        'PREPARING_REPO',
        'RUNNING_BASELINE',
        'INVOKING_AGENT',
        'COLLECTING_PATCH',
        'VALIDATING_PATCH',
        'APPLYING_PATCH',
        'RUNNING_VISIBLE_TESTS',
        'RUNNING_HIDDEN_TESTS',
        'ANALYZING_QUALITY',
        'SCORING',
        'COMPLETED',
        'FAILED',
        'CANCELLED',
        'TIMED_OUT'
    )),
    CONSTRAINT evaluations_failure_class_check CHECK (
        failure_class IS NULL OR failure_class IN ('INFRASTRUCTURE', 'AGENT', 'USER', 'BASELINE', 'INTERNAL')
    ),
    CONSTRAINT evaluations_attempt_check CHECK (attempt > 0 AND attempt <= 100),
    CONSTRAINT evaluations_idempotency_unique UNIQUE (org_id, idempotency_key)
);

CREATE INDEX evaluations_org_state_idx
    ON evaluations.evaluations (org_id, state, created_at DESC);
CREATE INDEX evaluations_org_id_idx
    ON evaluations.evaluations (org_id, id);
CREATE INDEX evaluations_task_idx
    ON evaluations.evaluations (org_id, task_id, created_at DESC);
CREATE INDEX evaluations_agent_idx
    ON evaluations.evaluations (org_id, agent_id, created_at DESC);

-- ---------------------------------------------------------------------------
-- Append-only state transitions (audit trail)
-- ---------------------------------------------------------------------------
CREATE TABLE evaluations.evaluation_state_transitions (
    id              BIGSERIAL PRIMARY KEY,
    evaluation_id   UUID NOT NULL REFERENCES evaluations.evaluations (id) ON DELETE CASCADE,
    org_id          UUID NOT NULL REFERENCES identity.organizations (id) ON DELETE CASCADE,
    from_state      TEXT,
    to_state        TEXT NOT NULL,
    reason          TEXT NOT NULL DEFAULT '',
    actor           TEXT NOT NULL DEFAULT 'system',
    attempt         INT NOT NULL DEFAULT 1,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX evaluation_transitions_eval_idx
    ON evaluations.evaluation_state_transitions (evaluation_id, created_at ASC);
CREATE INDEX evaluation_transitions_org_idx
    ON evaluations.evaluation_state_transitions (org_id, created_at DESC);

-- ---------------------------------------------------------------------------
-- Stage attempts (idempotent stage execution + duplicate protection)
-- ---------------------------------------------------------------------------
CREATE TABLE evaluations.evaluation_stage_attempts (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    evaluation_id   UUID NOT NULL REFERENCES evaluations.evaluations (id) ON DELETE CASCADE,
    org_id          UUID NOT NULL REFERENCES identity.organizations (id) ON DELETE CASCADE,
    state           TEXT NOT NULL,
    attempt         INT NOT NULL,
    message_id      TEXT NOT NULL,
    status          TEXT NOT NULL DEFAULT 'STARTED',
    started_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at     TIMESTAMPTZ,
    error_code      TEXT,
    error_message   TEXT,
    CONSTRAINT stage_attempts_unique UNIQUE (evaluation_id, state, attempt),
    CONSTRAINT stage_attempts_message_unique UNIQUE (message_id),
    CONSTRAINT stage_attempts_status_check CHECK (status IN ('STARTED', 'SUCCEEDED', 'FAILED', 'SKIPPED_DUPLICATE'))
);

CREATE INDEX stage_attempts_eval_idx
    ON evaluations.evaluation_stage_attempts (evaluation_id, started_at DESC);

-- ---------------------------------------------------------------------------
-- Patch artifacts (sealed once accepted)
-- ---------------------------------------------------------------------------
CREATE TABLE evaluations.patch_artifacts (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    evaluation_id   UUID NOT NULL UNIQUE REFERENCES evaluations.evaluations (id) ON DELETE CASCADE,
    org_id          UUID NOT NULL REFERENCES identity.organizations (id) ON DELETE CASCADE,
    content_digest  TEXT NOT NULL,
    storage_uri     TEXT NOT NULL,
    format          TEXT NOT NULL DEFAULT 'unified_diff',
    byte_size       INT NOT NULL,
    collected_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    sealed          BOOLEAN NOT NULL DEFAULT TRUE,
    CONSTRAINT patch_artifacts_format_check CHECK (format IN ('unified_diff', 'git_bundle')),
    CONSTRAINT patch_artifacts_size_check CHECK (byte_size >= 0)
);

-- ---------------------------------------------------------------------------
-- Test runs + case results
-- ---------------------------------------------------------------------------
CREATE TABLE evaluations.test_runs (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    evaluation_id   UUID NOT NULL REFERENCES evaluations.evaluations (id) ON DELETE CASCADE,
    org_id          UUID NOT NULL REFERENCES identity.organizations (id) ON DELETE CASCADE,
    phase           TEXT NOT NULL,
    suite_id        UUID,
    status          TEXT NOT NULL,
    started_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at     TIMESTAMPTZ,
    container_meta  JSONB NOT NULL DEFAULT '{}'::jsonb,
    CONSTRAINT test_runs_phase_check CHECK (phase IN (
        'BASELINE', 'VISIBLE_POST', 'HIDDEN_POST', 'QUALITY', 'REGRESSION'
    )),
    CONSTRAINT test_runs_status_check CHECK (status IN ('RUNNING', 'SUCCEEDED', 'FAILED')),
    CONSTRAINT test_runs_phase_unique UNIQUE (evaluation_id, phase)
);

CREATE INDEX test_runs_eval_idx ON evaluations.test_runs (evaluation_id, phase);

CREATE TABLE evaluations.test_case_results (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    test_run_id     UUID NOT NULL REFERENCES evaluations.test_runs (id) ON DELETE CASCADE,
    org_id          UUID NOT NULL REFERENCES identity.organizations (id) ON DELETE CASCADE,
    case_key        TEXT NOT NULL,
    status          TEXT NOT NULL,
    duration_ms     INT NOT NULL DEFAULT 0,
    transition      TEXT,
    message         TEXT NOT NULL DEFAULT '',
    log_excerpt_uri TEXT,
    CONSTRAINT test_case_results_unique UNIQUE (test_run_id, case_key),
    CONSTRAINT test_case_results_status_check CHECK (status IN ('PASS', 'FAIL', 'ERROR', 'SKIP')),
    CONSTRAINT test_case_results_transition_check CHECK (
        transition IS NULL OR transition IN ('F2P', 'P2P', 'P2F', 'F2F')
    )
);

CREATE INDEX test_case_results_run_idx ON evaluations.test_case_results (test_run_id);

-- ---------------------------------------------------------------------------
-- Quality / score scaffolding (populated in later milestones)
-- ---------------------------------------------------------------------------
CREATE TABLE evaluations.quality_reports (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    evaluation_id       UUID NOT NULL REFERENCES evaluations.evaluations (id) ON DELETE CASCADE,
    org_id              UUID NOT NULL REFERENCES identity.organizations (id) ON DELETE CASCADE,
    tool                TEXT NOT NULL,
    metrics_json        JSONB NOT NULL DEFAULT '{}'::jsonb,
    normalized_score    NUMERIC(5, 4),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE evaluations.evaluation_scores (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    evaluation_id       UUID NOT NULL UNIQUE REFERENCES evaluations.evaluations (id) ON DELETE CASCADE,
    org_id              UUID NOT NULL REFERENCES identity.organizations (id) ON DELETE CASCADE,
    weighted_total      NUMERIC(6, 4) NOT NULL DEFAULT 0,
    profile_id          TEXT NOT NULL DEFAULT 'default-v1',
    computed_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE evaluations.score_metrics (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    evaluation_score_id     UUID NOT NULL REFERENCES evaluations.evaluation_scores (id) ON DELETE CASCADE,
    metric_key              TEXT NOT NULL,
    raw_value               NUMERIC NOT NULL,
    weight                  NUMERIC NOT NULL,
    weighted_value          NUMERIC NOT NULL,
    CONSTRAINT score_metrics_unique UNIQUE (evaluation_score_id, metric_key)
);

-- ---------------------------------------------------------------------------
-- Transactional outbox + processed message guard
-- ---------------------------------------------------------------------------
CREATE TABLE evaluations.outbox_events (
    id              BIGSERIAL PRIMARY KEY,
    aggregate_type  TEXT NOT NULL,
    aggregate_id    UUID NOT NULL,
    org_id          UUID NOT NULL REFERENCES identity.organizations (id) ON DELETE CASCADE,
    event_type      TEXT NOT NULL,
    payload         JSONB NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at    TIMESTAMPTZ,
    publish_attempts INT NOT NULL DEFAULT 0
);

CREATE INDEX outbox_unpublished_idx
    ON evaluations.outbox_events (created_at ASC)
    WHERE published_at IS NULL;

CREATE TABLE evaluations.processed_messages (
    message_id      TEXT PRIMARY KEY,
    evaluation_id   UUID NOT NULL,
    org_id          UUID NOT NULL,
    processed_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

INSERT INTO platform.schema_marker (migration_label)
VALUES ('M4_evaluations');
