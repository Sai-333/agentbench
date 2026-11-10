-- Opt-in public leaderboard publications (org-private remains default).
-- Single Flyway stream; forward-only; do not edit V1–V10.

CREATE SCHEMA IF NOT EXISTS analytics;

COMMENT ON SCHEMA analytics IS 'Analytics aggregates and opt-in public leaderboard publications.';

CREATE TABLE analytics.leaderboard_publications (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id                  UUID NOT NULL REFERENCES identity.organizations (id) ON DELETE CASCADE,
    benchmark_version_id    UUID NOT NULL REFERENCES benchmarks.benchmark_versions (id) ON DELETE CASCADE,
    public_slug             CITEXT NOT NULL,
    title                   TEXT NOT NULL,
    published_at            TIMESTAMPTZ NOT NULL,
    published_by            UUID NOT NULL REFERENCES identity.users (id),
    unpublished_at          TIMESTAMPTZ,
    unpublished_by          UUID REFERENCES identity.users (id),
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT leaderboard_publications_org_version_unique UNIQUE (org_id, benchmark_version_id),
    CONSTRAINT leaderboard_publications_slug_unique UNIQUE (public_slug),
    CONSTRAINT leaderboard_publications_slug_format CHECK (
        public_slug ~ '^[a-z0-9]+(?:-[a-z0-9]+)*$'
        AND char_length(public_slug) BETWEEN 3 AND 80
    ),
    CONSTRAINT leaderboard_publications_title_len CHECK (char_length(title) BETWEEN 1 AND 200)
);

CREATE INDEX leaderboard_publications_org_idx
    ON analytics.leaderboard_publications (org_id, published_at DESC);

CREATE INDEX leaderboard_publications_active_slug_idx
    ON analytics.leaderboard_publications (public_slug)
    WHERE unpublished_at IS NULL;

INSERT INTO platform.schema_marker (migration_label)
VALUES ('public_leaderboards');
