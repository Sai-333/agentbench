# AgentBench — SAML 2.0 enterprise SSO

Local email/password JWT remains the default. Optional **Google/GitHub OIDC** stays unchanged (`docs/AUTH_OIDC.md`). This document covers the optional **SAML 2.0 Service Provider** path for enterprise IdPs (Okta, Azure AD, ADFS, etc.).

## Status (honest)

| Capability | Status |
|---|---|
| SP-initiated login (HTTP-Redirect AuthnRequest → HTTP-POST ACS) | **Landed** |
| IdP metadata via URL or inline XML | **Landed** |
| Attribute map for email / display name | **Landed** (env/props) |
| Same JWT pair as local/OIDC after link/create | **Landed** |
| Default off for local Compose | **Yes** |
| Multi-IdP admin UI / per-org IdP console | **Not built** — one IdP via config |
| Signed AuthnRequests / SP signing keys | **Not configured** — AuthnRequests unsigned; IdP must allow that |
| Encrypted assertions | **Not claimed** — signed assertions expected |

## How it works

1. Frontend loads `GET /api/v1/auth/providers` and shows **Continue with Enterprise SSO** when SAML is enabled and metadata is configured (`type=saml`).
2. Browser hits `GET /api/v1/auth/saml/login` → 302 to the IdP SSO URL with `SAMLRequest` + CSRF `RelayState`.
3. IdP POSTs to `POST /api/v1/auth/saml/acs` with `SAMLResponse` + `RelayState`.
4. Backend validates the assertion (Spring Security OpenSAML), **links or creates** a local `identity.users` row, stores `identity.user_identities` (`provider=saml`), issues JWTs.
5. Browser is redirected to `AGENTBENCH_SAML_SUCCESS_REDIRECT_URL` with tokens in the **URL fragment** (same pattern as OIDC).

API / CI alternative: after `/login`, call  
`POST /api/v1/auth/saml/token` with `{"samlResponse","relayState"}` → `TokenResponse`.

SP metadata for IdP registration: `GET /api/v1/auth/saml/metadata`.

## Enable (Compose / `.env`)

```bash
cp .env.example .env
```

```bash
AGENTBENCH_SAML_ENABLED=true
AGENTBENCH_SAML_ENTITY_ID=http://localhost:8080/saml2/metadata
AGENTBENCH_SAML_ACS_URL=http://localhost:8080/api/v1/auth/saml/acs
AGENTBENCH_SAML_METADATA_URL=https://idp.example.com/metadata
AGENTBENCH_SAML_SUCCESS_REDIRECT_URL=http://localhost:3000/
AGENTBENCH_SAML_DISPLAY_NAME=Enterprise SSO
# Attribute names as released by your IdP:
AGENTBENCH_SAML_ATTR_EMAIL=email
AGENTBENCH_SAML_ATTR_DISPLAY_NAME=displayName
```

Inline metadata (no URL fetch) — useful for air-gapped or fixtures:

```bash
AGENTBENCH_SAML_METADATA_XML='<?xml version="1.0"?>...'
```

Leave `AGENTBENCH_SAML_ENABLED=false` (default) for local demos; password + optional OIDC stay available.

At the IdP, register:

- **SP Entity ID** = `AGENTBENCH_SAML_ENTITY_ID`
- **ACS URL** = `AGENTBENCH_SAML_ACS_URL` (HTTP-POST)
- Allow **unsigned AuthnRequests** (AgentBench does not ship SP signing keys in this milestone)
- Release email (and optionally display name) attributes matching the attribute map

## Account linking

| Situation | Result |
|---|---|
| First SAML login | Creates local user (`password_hash` null) + identity row |
| Same SAML NameID again | Reuses linked user |
| SAML email matches existing password user | Links identity; password login still works |
| Provider not configured | `/login`, ACS, and UI button unavailable |

## Residual limits

- **One IdP** via env/application props — no multi-IdP admin UI, no per-tenant IdP picker.
- AuthnRequests are **unsigned**; IdPs that require signed AuthnRequests need a follow-up (SP signing credential config).
- Assertion **encryption** is not claimed; configure the IdP to send signed (optionally unencrypted) assertions the SP can validate from metadata certs.

## Tests

`SamlAuthIntegrationTest` loads IdP metadata from WireMock and validates locally signed assertions — CI does **not** need a live IdP.
