-- Milestone 2: identity, orgs, memberships, secrets, projects, repo config.
-- Single Flyway stream; forward-only; do not edit V1.

CREATE SCHEMA IF NOT EXISTS identity;
CREATE SCHEMA IF NOT EXISTS projects;

COMMENT ON SCHEMA identity IS 'Users, organizations, memberships, tokens, secret refs.';
COMMENT ON SCHEMA projects IS 'Projects, repository connections, and snapshots.';

-- ---------------------------------------------------------------------------
-- Users
-- ---------------------------------------------------------------------------
CREATE TABLE identity.users (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email           CITEXT NOT NULL,
    password_hash   TEXT,
    display_name    TEXT NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT users_email_unique UNIQUE (email)
);

CREATE INDEX users_created_at_idx ON identity.users (created_at DESC);

-- ---------------------------------------------------------------------------
-- Organizations
-- ---------------------------------------------------------------------------
CREATE TABLE identity.organizations (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name            TEXT NOT NULL,
    slug            CITEXT NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT organizations_slug_unique UNIQUE (slug)
);

CREATE INDEX organizations_created_at_idx ON identity.organizations (created_at DESC);

-- ---------------------------------------------------------------------------
-- Memberships (org-scoped RBAC)
-- Roles: OWNER, ADMIN, DEVELOPER, VIEWER
-- ---------------------------------------------------------------------------
CREATE TABLE identity.memberships (
    org_id          UUID NOT NULL REFERENCES identity.organizations (id) ON DELETE CASCADE,
    user_id         UUID NOT NULL REFERENCES identity.users (id) ON DELETE CASCADE,
    role            TEXT NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (org_id, user_id),
    CONSTRAINT memberships_role_check CHECK (role IN ('OWNER', 'ADMIN', 'DEVELOPER', 'VIEWER'))
);

CREATE INDEX memberships_user_id_idx ON identity.memberships (user_id);

-- ---------------------------------------------------------------------------
-- Refresh tokens (hashed; revocation via deleted/revoked_at)
-- ---------------------------------------------------------------------------
CREATE TABLE identity.refresh_tokens (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         UUID NOT NULL REFERENCES identity.users (id) ON DELETE CASCADE,
    token_hash      TEXT NOT NULL,
    expires_at      TIMESTAMPTZ NOT NULL,
    revoked_at      TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT refresh_tokens_token_hash_unique UNIQUE (token_hash)
);

CREATE INDEX refresh_tokens_user_id_idx ON identity.refresh_tokens (user_id);

-- ---------------------------------------------------------------------------
-- Encrypted secret refs (API never returns plaintext/ciphertext)
-- ---------------------------------------------------------------------------
CREATE TABLE identity.secret_refs (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id          UUID NOT NULL REFERENCES identity.organizations (id) ON DELETE CASCADE,
    name            TEXT NOT NULL,
    ciphertext      BYTEA NOT NULL,
    key_version     INT NOT NULL DEFAULT 1,
    purpose         TEXT NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    rotated_at      TIMESTAMPTZ,
    CONSTRAINT secret_refs_org_name_unique UNIQUE (org_id, name),
    CONSTRAINT secret_refs_purpose_check CHECK (purpose IN ('GIT', 'AGENT', 'OTHER'))
);

CREATE INDEX secret_refs_org_id_idx ON identity.secret_refs (org_id);

-- ---------------------------------------------------------------------------
-- Projects
-- ---------------------------------------------------------------------------
CREATE TABLE projects.projects (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id          UUID NOT NULL REFERENCES identity.organizations (id) ON DELETE CASCADE,
    name            TEXT NOT NULL,
    description     TEXT NOT NULL DEFAULT '',
    created_by      UUID NOT NULL REFERENCES identity.users (id),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT projects_org_name_unique UNIQUE (org_id, name)
);

CREATE INDEX projects_org_id_idx ON projects.projects (org_id, created_at DESC);

-- ---------------------------------------------------------------------------
-- GitHub / git repository connection (live config under a project)
-- Credentials live in secret_refs; never store raw tokens here.
-- ---------------------------------------------------------------------------
CREATE TABLE projects.project_repositories (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id              UUID NOT NULL REFERENCES identity.organizations (id) ON DELETE CASCADE,
    project_id          UUID NOT NULL REFERENCES projects.projects (id) ON DELETE CASCADE,
    git_url             TEXT NOT NULL,
    default_branch      TEXT NOT NULL DEFAULT 'main',
    commit_sha          CHAR(40),
    language            TEXT,
    build_command       TEXT,
    test_command        TEXT,
    secret_ref_id       UUID REFERENCES identity.secret_refs (id) ON DELETE SET NULL,
    validation_status   TEXT NOT NULL DEFAULT 'UNKNOWN',
    validation_message  TEXT,
    last_validated_at   TIMESTAMPTZ,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT project_repositories_project_unique UNIQUE (project_id),
    CONSTRAINT project_repositories_validation_check CHECK (
        validation_status IN ('UNKNOWN', 'VALID', 'INVALID', 'SKIPPED')
    )
);

CREATE INDEX project_repositories_org_id_idx ON projects.project_repositories (org_id);

-- ---------------------------------------------------------------------------
-- Immutable repo snapshots (pin metadata; clone/archive storage later)
-- ---------------------------------------------------------------------------
CREATE TABLE projects.repo_snapshots (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id          UUID NOT NULL REFERENCES identity.organizations (id) ON DELETE CASCADE,
    project_id      UUID NOT NULL REFERENCES projects.projects (id) ON DELETE CASCADE,
    git_url         TEXT,
    commit_sha      CHAR(40),
    branch          TEXT,
    content_digest  TEXT NOT NULL DEFAULT '',
    storage_uri     TEXT NOT NULL DEFAULT '',
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX repo_snapshots_org_project_idx ON projects.repo_snapshots (org_id, project_id, created_at DESC);

INSERT INTO platform.schema_marker (migration_label)
VALUES ('M2_identity_projects');
