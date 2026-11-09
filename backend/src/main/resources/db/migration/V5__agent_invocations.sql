-- Milestone 5: agent provider integration — invocation audit + expanded adapter types.
-- Single Flyway stream; forward-only; do not edit V1–V4.

COMMENT ON SCHEMA agents IS 'Org-scoped agent registry and provider invocation audit (M5).';

-- Expand adapter types: keep FAKE/HTTP/SPRING_AI; add OPENAI_COMPATIBLE and CODING_CLI.
ALTER TABLE agents.agents DROP CONSTRAINT agents_adapter_type_check;
ALTER TABLE agents.agents ADD CONSTRAINT agents_adapter_type_check
    CHECK (adapter_type IN ('FAKE', 'HTTP', 'SPRING_AI', 'OPENAI_COMPATIBLE', 'CODING_CLI'));

-- Per-execution agent invocation capture (provider, model, tokens, cost, retries, status).
-- Never store API keys here — only secret_ref_id on agents.agents.
CREATE TABLE agents.agent_invocations (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id                  UUID NOT NULL REFERENCES identity.organizations (id) ON DELETE CASCADE,
    evaluation_id           UUID NOT NULL REFERENCES evaluations.evaluations (id) ON DELETE CASCADE,
    agent_id                UUID NOT NULL REFERENCES agents.agents (id) ON DELETE RESTRICT,
    task_id                 UUID NOT NULL REFERENCES benchmarks.tasks (id) ON DELETE RESTRICT,
    provider                TEXT NOT NULL,
    model                   TEXT,
    provider_version        TEXT,
    prompt                  TEXT,
    status                  TEXT NOT NULL,
    attempt                 INT NOT NULL DEFAULT 1,
    max_attempts            INT NOT NULL DEFAULT 1,
    started_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at             TIMESTAMPTZ,
    patch_digest            TEXT,
    patch_byte_size         INT,
    patch_storage_uri       TEXT,
    prompt_tokens           INT,
    completion_tokens       INT,
    total_tokens            INT,
    estimated_cost_usd      NUMERIC(18, 8),
    error_code              TEXT,
    error_message           TEXT,
    meta_json               JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT agent_invocations_status_check CHECK (status IN (
        'RUNNING', 'SUCCEEDED', 'FAILED', 'TIMED_OUT', 'CANCELLED'
    )),
    CONSTRAINT agent_invocations_attempt_check CHECK (attempt > 0 AND max_attempts > 0)
);

CREATE INDEX agent_invocations_evaluation_idx
    ON agents.agent_invocations (evaluation_id, created_at DESC);
CREATE INDEX agent_invocations_org_agent_idx
    ON agents.agent_invocations (org_id, agent_id, created_at DESC);

COMMENT ON TABLE agents.agent_invocations IS
    'Audit of agent provider calls. Secrets must never appear in prompt/meta/error fields.';
