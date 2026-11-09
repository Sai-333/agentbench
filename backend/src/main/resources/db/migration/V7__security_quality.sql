-- Milestone 7: security hardening markers + artifact retention audit.
-- Single Flyway stream; forward-only; do not edit V1–V6.

-- ---------------------------------------------------------------------------
-- Artifact retention / cleanup audit (local volume policy)
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS platform.artifact_cleanup_runs (
    id              BIGSERIAL PRIMARY KEY,
    started_at      TIMESTAMPTZ NOT NULL,
    finished_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_files   INT NOT NULL DEFAULT 0,
    freed_bytes     BIGINT NOT NULL DEFAULT 0,
    status          TEXT NOT NULL,
    message         TEXT NOT NULL DEFAULT '',
    CONSTRAINT artifact_cleanup_status_check CHECK (status IN ('SUCCEEDED', 'FAILED', 'SKIPPED'))
);

COMMENT ON TABLE platform.artifact_cleanup_runs IS
    'Audit log for artifact retention sweeps (M7). Policy lives in agentbench.artifacts.retention.*';

-- Quality reports may include multi-tool aggregates; tool column stays free-form.
COMMENT ON TABLE evaluations.quality_reports IS
    'Static analysis reports (Checkstyle/SpotBugs/PMD aggregate in M7). Sonar optional stub only.';

-- Track execution isolation metadata on test runs is already JSONB container_meta.
-- No schema change required for Docker hardening flags (stored in container_meta / metrics_json).

INSERT INTO platform.schema_marker (migration_label)
VALUES ('M7_security_quality');
