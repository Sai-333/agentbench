-- Milestone 6: evaluation scoring engine — profiles, score metadata, flaky hooks.
-- Single Flyway stream; forward-only; do not edit V1–V5.

-- ---------------------------------------------------------------------------
-- Versioned scoring profiles (weights snapshotted onto score_metrics at compute)
-- ---------------------------------------------------------------------------
CREATE TABLE evaluations.scoring_profiles (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    profile_key     TEXT NOT NULL UNIQUE,
    display_name    TEXT NOT NULL,
    description     TEXT NOT NULL DEFAULT '',
    weights_json    JSONB NOT NULL,
    flaky_repeat_runs INT NOT NULL DEFAULT 1,
    active          BOOLEAN NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT scoring_profiles_flaky_check CHECK (flaky_repeat_runs >= 1 AND flaky_repeat_runs <= 10)
);

COMMENT ON TABLE evaluations.scoring_profiles IS
    'Versioned weight profiles. Evaluations snapshot weights into score_metrics; historical scores never drift.';

INSERT INTO evaluations.scoring_profiles (id, profile_key, display_name, description, weights_json, flaky_repeat_runs)
VALUES (
    'a0000000-0000-4000-8000-000000000001',
    'default-v1',
    'Default v1',
    'Product brief defaults: target 30%, regression 25%, hidden 20%, quality 10%, edge 10%, efficiency 5%.',
    '{
      "target_failure_resolution": 0.30,
      "regression": 0.25,
      "hidden": 0.20,
      "code_quality": 0.10,
      "edge_case": 0.10,
      "efficiency": 0.05
    }'::jsonb,
    1
);

-- Enrich evaluation_scores: validity + transition/raw snapshots for transparency.
ALTER TABLE evaluations.evaluation_scores
    ADD COLUMN IF NOT EXISTS score_status TEXT NOT NULL DEFAULT 'VALID',
    ADD COLUMN IF NOT EXISTS profile_key TEXT NOT NULL DEFAULT 'default-v1',
    ADD COLUMN IF NOT EXISTS transition_summary_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    ADD COLUMN IF NOT EXISTS raw_summary_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    ADD COLUMN IF NOT EXISTS warnings_json JSONB NOT NULL DEFAULT '[]'::jsonb;

ALTER TABLE evaluations.evaluation_scores
    DROP CONSTRAINT IF EXISTS evaluation_scores_status_check;
ALTER TABLE evaluations.evaluation_scores
    ADD CONSTRAINT evaluation_scores_status_check CHECK (
        score_status IN ('VALID', 'INVALID_BASELINE', 'INCOMPLETE', 'INFRA_FAILURE', 'SKIPPED')
    );

-- Allow NULL weighted_total when score is not valid/meaningful.
ALTER TABLE evaluations.evaluation_scores
    ALTER COLUMN weighted_total DROP NOT NULL;

-- Metric row metadata (stub flags, notes) without inventing fake quality scores.
ALTER TABLE evaluations.score_metrics
    ADD COLUMN IF NOT EXISTS meta_json JSONB NOT NULL DEFAULT '{}'::jsonb;

-- Case category + flaky markers on results (visible transitions remain F2P/P2P/P2F/F2F only).
ALTER TABLE evaluations.test_case_results
    ADD COLUMN IF NOT EXISTS category TEXT,
    ADD COLUMN IF NOT EXISTS flaky BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS attempt_count INT NOT NULL DEFAULT 1,
    ADD COLUMN IF NOT EXISTS meta_json JSONB NOT NULL DEFAULT '{}'::jsonb;

ALTER TABLE evaluations.test_case_results
    DROP CONSTRAINT IF EXISTS test_case_results_category_check;
ALTER TABLE evaluations.test_case_results
    ADD CONSTRAINT test_case_results_category_check CHECK (
        category IS NULL OR category IN ('TARGET', 'CONTROL', 'EDGE', 'HIDDEN', 'OTHER')
    );

-- Flaky detection attempt log (design + basic impl for configurable repeats).
CREATE TABLE evaluations.flaky_detection_attempts (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    evaluation_id   UUID NOT NULL REFERENCES evaluations.evaluations (id) ON DELETE CASCADE,
    org_id          UUID NOT NULL REFERENCES identity.organizations (id) ON DELETE CASCADE,
    test_run_id     UUID NOT NULL REFERENCES evaluations.test_runs (id) ON DELETE CASCADE,
    phase           TEXT NOT NULL,
    attempt_index   INT NOT NULL,
    case_key        TEXT NOT NULL,
    status          TEXT NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT flaky_attempts_status_check CHECK (status IN ('PASS', 'FAIL', 'ERROR', 'SKIP')),
    CONSTRAINT flaky_attempts_index_check CHECK (attempt_index >= 1)
);

CREATE INDEX flaky_attempts_eval_idx
    ON evaluations.flaky_detection_attempts (evaluation_id, phase, case_key, attempt_index);

INSERT INTO platform.schema_marker (migration_label)
VALUES ('M6_scoring_engine');
