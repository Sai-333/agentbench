-- Allow SAML beside Google/GitHub on external identity + login-state tables.
-- Single Flyway stream; forward-only; do not edit V1–V13.

ALTER TABLE identity.user_identities
    DROP CONSTRAINT IF EXISTS user_identities_provider_check;

ALTER TABLE identity.user_identities
    ADD CONSTRAINT user_identities_provider_check
        CHECK (provider IN ('google', 'github', 'saml'));

ALTER TABLE identity.oidc_login_states
    DROP CONSTRAINT IF EXISTS oidc_login_states_provider_check;

ALTER TABLE identity.oidc_login_states
    ADD CONSTRAINT oidc_login_states_provider_check
        CHECK (provider IN ('google', 'github', 'saml'));

COMMENT ON TABLE identity.user_identities IS
    'Links local users to external OIDC/OAuth/SAML subjects (Google, GitHub, SAML).';

COMMENT ON TABLE identity.oidc_login_states IS
    'One-time OAuth/SAML state (RelayState) values for CSRF protection during federated login.';
