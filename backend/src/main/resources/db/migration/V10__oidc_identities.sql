-- OIDC / OAuth external identities + short-lived login state for authorization-code flow.

CREATE TABLE identity.user_identities (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         UUID NOT NULL REFERENCES identity.users (id) ON DELETE CASCADE,
    provider        TEXT NOT NULL,
    subject         TEXT NOT NULL,
    email           CITEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT user_identities_provider_subject_unique UNIQUE (provider, subject),
    CONSTRAINT user_identities_provider_check CHECK (provider IN ('google', 'github'))
);

CREATE INDEX user_identities_user_id_idx ON identity.user_identities (user_id);
CREATE INDEX user_identities_email_idx ON identity.user_identities (email);

COMMENT ON TABLE identity.user_identities IS
    'Links local users to external OIDC/OAuth subjects (Google, GitHub).';

CREATE TABLE identity.oidc_login_states (
    state           TEXT PRIMARY KEY,
    provider        TEXT NOT NULL,
    nonce           TEXT,
    redirect_to     TEXT,
    expires_at      TIMESTAMPTZ NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    consumed_at     TIMESTAMPTZ,
    CONSTRAINT oidc_login_states_provider_check CHECK (provider IN ('google', 'github'))
);

CREATE INDEX oidc_login_states_expires_at_idx ON identity.oidc_login_states (expires_at);

COMMENT ON TABLE identity.oidc_login_states IS
    'One-time OAuth state values for CSRF protection during authorization-code login.';
