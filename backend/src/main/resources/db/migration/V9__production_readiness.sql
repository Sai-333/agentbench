-- Milestone 9: production readiness markers (recovery indexes / ops).
-- Single Flyway stream; forward-only; do not edit V1–V8.

-- Speed timeout watchdog + cancel sweeps on non-terminal rows.
CREATE INDEX IF NOT EXISTS evaluations_active_started_idx
    ON evaluations.evaluations (started_at)
    WHERE state NOT IN ('COMPLETED', 'FAILED', 'CANCELLED', 'TIMED_OUT');

CREATE INDEX IF NOT EXISTS evaluations_cancel_requested_idx
    ON evaluations.evaluations (org_id, cancel_requested)
    WHERE cancel_requested = TRUE
      AND state NOT IN ('COMPLETED', 'FAILED', 'CANCELLED', 'TIMED_OUT');

INSERT INTO platform.schema_marker (migration_label)
VALUES ('M9_production_readiness');
