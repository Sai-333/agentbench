-- Anonymous-capable comments + up/down votes on published public leaderboards.
-- Single Flyway stream; forward-only; do not edit V1–V20.
-- API layer enforces published-only; org-private boards never expose these routes.
-- Moderation: soft-delete comments (OWNER/ADMIN). Rate limits / size limits in app config.

CREATE TABLE analytics.leaderboard_comments (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id              UUID NOT NULL REFERENCES identity.organizations (id) ON DELETE CASCADE,
    publication_id      UUID NOT NULL REFERENCES analytics.leaderboard_publications (id) ON DELETE CASCADE,
    body                TEXT NOT NULL,
    display_name        TEXT NOT NULL,
    author_user_id      UUID REFERENCES identity.users (id) ON DELETE SET NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at          TIMESTAMPTZ,
    deleted_by          UUID REFERENCES identity.users (id) ON DELETE SET NULL,
    CONSTRAINT leaderboard_comments_body_len CHECK (char_length(body) BETWEEN 1 AND 2000),
    CONSTRAINT leaderboard_comments_display_name_len CHECK (char_length(display_name) BETWEEN 1 AND 80)
);

COMMENT ON TABLE analytics.leaderboard_comments IS
    'Public-board comments (anonymous or authenticated). Soft-deleted by org OWNER/ADMIN moderation.';

CREATE INDEX leaderboard_comments_pub_created_idx
    ON analytics.leaderboard_comments (publication_id, created_at DESC)
    WHERE deleted_at IS NULL;

CREATE INDEX leaderboard_comments_org_idx
    ON analytics.leaderboard_comments (org_id, created_at DESC);

CREATE TABLE analytics.leaderboard_votes (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id              UUID NOT NULL REFERENCES identity.organizations (id) ON DELETE CASCADE,
    publication_id      UUID NOT NULL REFERENCES analytics.leaderboard_publications (id) ON DELETE CASCADE,
    voter_key           TEXT NOT NULL,
    value               SMALLINT NOT NULL,
    author_user_id      UUID REFERENCES identity.users (id) ON DELETE SET NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT leaderboard_votes_value_check CHECK (value IN (-1, 1)),
    CONSTRAINT leaderboard_votes_voter_key_len CHECK (char_length(voter_key) BETWEEN 3 AND 128),
    CONSTRAINT leaderboard_votes_pub_voter_unique UNIQUE (publication_id, voter_key)
);

COMMENT ON TABLE analytics.leaderboard_votes IS
    'Board-level up (+1) / down (-1) votes. voter_key is user:<uuid> when authenticated, else anon:<fingerprint>.';

COMMENT ON COLUMN analytics.leaderboard_votes.voter_key IS
    'Stable voter identity for upsert uniqueness: user:<uuid> or anon:<client-fingerprint>.';

CREATE INDEX leaderboard_votes_pub_idx
    ON analytics.leaderboard_votes (publication_id);

CREATE INDEX leaderboard_votes_org_idx
    ON analytics.leaderboard_votes (org_id);

ALTER TABLE analytics.leaderboard_comments ENABLE ROW LEVEL SECURITY;
ALTER TABLE analytics.leaderboard_comments FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation ON analytics.leaderboard_comments;
CREATE POLICY tenant_isolation ON analytics.leaderboard_comments
    FOR ALL
    USING (app.tenant_ok(org_id))
    WITH CHECK (app.tenant_ok(org_id));

ALTER TABLE analytics.leaderboard_votes ENABLE ROW LEVEL SECURITY;
ALTER TABLE analytics.leaderboard_votes FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation ON analytics.leaderboard_votes;
CREATE POLICY tenant_isolation ON analytics.leaderboard_votes
    FOR ALL
    USING (app.tenant_ok(org_id))
    WITH CHECK (app.tenant_ok(org_id));

GRANT SELECT, INSERT, UPDATE, DELETE ON analytics.leaderboard_comments TO agentbench_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON analytics.leaderboard_votes TO agentbench_app;

INSERT INTO platform.schema_marker (migration_label)
VALUES ('public_leaderboard_comments_votes');
