-- Artifact TTL / GC: purge markers on patch metadata + cleanup audit counters.
-- Single Flyway stream; forward-only; do not edit V1–V16.
-- Blobs remain behind ArtifactStorage (local / S3); this tracks DB metadata expiry.

ALTER TABLE evaluations.patch_artifacts
    ADD COLUMN IF NOT EXISTS purged_at TIMESTAMPTZ;

COMMENT ON COLUMN evaluations.patch_artifacts.purged_at IS
    'Set when ArtifactRetentionService deletes the blob under TTL; storage_uri may be cleared.';

CREATE INDEX IF NOT EXISTS patch_artifacts_ttl_idx
    ON evaluations.patch_artifacts (collected_at)
    WHERE purged_at IS NULL;

ALTER TABLE platform.artifact_cleanup_runs
    ADD COLUMN IF NOT EXISTS deleted_metadata INT NOT NULL DEFAULT 0;

COMMENT ON COLUMN platform.artifact_cleanup_runs.deleted_metadata IS
    'Count of DB metadata rows purged (patch_artifacts / invocation URIs) in the sweep.';

INSERT INTO platform.schema_marker (migration_label)
VALUES ('artifact_ttl_gc');
