-- Watermark for optional S3 Object Lock audit export.
-- Does NOT mutate platform.worm_audit_events (WORM triggers deny UPDATE/DELETE).
-- Export is opt-in via agentbench.artifacts.s3.object-lock; default Compose stays Postgres-only WORM.

CREATE TABLE platform.worm_audit_s3_export_state (
    id              SMALLINT PRIMARY KEY DEFAULT 1 CHECK (id = 1),
    last_exported_id BIGINT NOT NULL DEFAULT 0,
    last_export_at  TIMESTAMPTZ,
    last_export_count INT NOT NULL DEFAULT 0,
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

COMMENT ON TABLE platform.worm_audit_s3_export_state IS
    'Singleton watermark for S3 Object Lock audit export; mutable ops state, not WORM evidence.';

INSERT INTO platform.worm_audit_s3_export_state (id, last_exported_id, last_export_count)
VALUES (1, 0, 0)
ON CONFLICT (id) DO NOTHING;

GRANT SELECT, INSERT, UPDATE ON platform.worm_audit_s3_export_state TO agentbench_app;
