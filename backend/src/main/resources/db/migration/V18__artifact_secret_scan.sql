-- Artifact secret scanning audit (R7). Records pattern hits without storing secret values.
-- Single Flyway stream; forward-only; do not edit V1–V17.

CREATE TABLE IF NOT EXISTS platform.artifact_secret_findings (
    id              BIGSERIAL PRIMARY KEY,
    detected_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    storage_key     TEXT NOT NULL DEFAULT '',
    content_type    TEXT NOT NULL DEFAULT '',
    finding_count   INT NOT NULL DEFAULT 0,
    pattern_ids     TEXT NOT NULL DEFAULT '',
    action          TEXT NOT NULL,
    message         TEXT NOT NULL DEFAULT '',
    CONSTRAINT artifact_secret_findings_action_check
        CHECK (action IN ('REDACTED', 'QUARANTINED', 'SKIPPED'))
);

COMMENT ON TABLE platform.artifact_secret_findings IS
    'Audit of secret-pattern hits on artifact persist. Never stores raw secret values — pattern ids + counts only.';

COMMENT ON COLUMN platform.artifact_secret_findings.pattern_ids IS
    'Comma-separated detector ids (e.g. aws_access_key,jwt,private_key). No matched substrings.';

COMMENT ON COLUMN platform.artifact_secret_findings.action IS
    'REDACTED = stored after redaction; QUARANTINED = persist refused; SKIPPED = scanner disabled or non-text.';

CREATE INDEX IF NOT EXISTS artifact_secret_findings_detected_idx
    ON platform.artifact_secret_findings (detected_at DESC);

INSERT INTO platform.schema_marker (migration_label)
VALUES ('artifact_secret_scan');
