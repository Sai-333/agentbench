-- Multi-IdP SAML configuration (org-scoped). Env/props single IdP remains fallback when none enabled.
-- Forward-only; do not edit V1–V15.

CREATE TABLE identity.saml_identity_providers (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id              UUID NOT NULL REFERENCES identity.organizations (id) ON DELETE CASCADE,
    registration_id     TEXT NOT NULL,
    display_name        TEXT NOT NULL,
    metadata_url        TEXT,
    metadata_xml        TEXT,
    attr_email          TEXT NOT NULL DEFAULT 'email',
    attr_display_name   TEXT NOT NULL DEFAULT 'displayName',
    enabled             BOOLEAN NOT NULL DEFAULT TRUE,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT saml_identity_providers_registration_id_format
        CHECK (registration_id ~ '^[a-z0-9]+(?:-[a-z0-9]+)*$'),
    CONSTRAINT saml_identity_providers_registration_id_len
        CHECK (char_length(registration_id) BETWEEN 2 AND 63),
    CONSTRAINT saml_identity_providers_display_name_len
        CHECK (char_length(display_name) BETWEEN 1 AND 120),
    CONSTRAINT saml_identity_providers_metadata_present
        CHECK (
            (metadata_url IS NOT NULL AND btrim(metadata_url) <> '')
            OR (metadata_xml IS NOT NULL AND btrim(metadata_xml) <> '')
        ),
    CONSTRAINT saml_identity_providers_org_registration_unique UNIQUE (org_id, registration_id),
    CONSTRAINT saml_identity_providers_registration_unique UNIQUE (registration_id)
);

CREATE INDEX saml_identity_providers_org_id_idx
    ON identity.saml_identity_providers (org_id);

CREATE INDEX saml_identity_providers_enabled_idx
    ON identity.saml_identity_providers (enabled)
    WHERE enabled = TRUE;

COMMENT ON TABLE identity.saml_identity_providers IS
    'Org-scoped SAML IdP configs managed by OWNER/ADMIN; registration_id is global for login URLs.';

-- Login-state provider may be "saml" (env fallback) or "saml:<registrationId>" (DB IdP).
ALTER TABLE identity.oidc_login_states
    DROP CONSTRAINT IF EXISTS oidc_login_states_provider_check;

ALTER TABLE identity.oidc_login_states
    ADD CONSTRAINT oidc_login_states_provider_check
        CHECK (
            provider IN ('google', 'github', 'saml')
            OR provider ~ '^saml:[a-z0-9]+(?:-[a-z0-9]+)*$'
        );

-- RLS defense-in-depth (same pattern as secret_refs).
ALTER TABLE identity.saml_identity_providers ENABLE ROW LEVEL SECURITY;
ALTER TABLE identity.saml_identity_providers FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation ON identity.saml_identity_providers;
CREATE POLICY tenant_isolation ON identity.saml_identity_providers
    FOR ALL
    USING (app.tenant_ok(org_id))
    WITH CHECK (app.tenant_ok(org_id));

GRANT SELECT, INSERT, UPDATE, DELETE ON identity.saml_identity_providers TO agentbench_app;
