# AgentBench — SAML 2.0 enterprise SSO

Local email/password JWT remains the default. Optional **Google/GitHub OIDC** stays unchanged (`docs/AUTH_OIDC.md`). This document covers the optional **SAML 2.0 Service Provider** path for enterprise IdPs (Okta, Azure AD, ADFS, etc.), including **org-scoped multi-IdP** admin APIs/UI.

## Status (honest)

| Capability | Status |
|---|---|
| SP-initiated login (HTTP-Redirect AuthnRequest → HTTP-POST ACS) | **Landed** |
| IdP metadata via URL or inline XML | **Landed** |
| Attribute map for email / display name | **Landed** (env/props + per-IdP admin) |
| Same JWT pair as local/OIDC after link/create | **Landed** |
| Default off for local Compose | **Yes** |
| Multi-IdP admin UI / org-scoped IdP console | **Landed** — OWNER/ADMIN CRUD; login lists enabled IdPs |
| Env/props single IdP fallback when DB empty | **Landed** |
| Signed AuthnRequests / SP signing keys | **Not configured** — AuthnRequests unsigned; IdP must allow that |
| Encrypted assertions | **Not claimed** — signed assertions expected |

## How it works

1. Frontend loads `GET /api/v1/auth/providers` and shows **Continue with …** for each enabled SAML IdP (`type=saml`).
   - If any **enabled** DB IdPs exist → those are listed (`/api/v1/auth/saml/login/{registrationId}`).
   - If none → env/props single IdP is listed when configured (`/api/v1/auth/saml/login`).
2. Browser hits the authorize path → 302 to the IdP SSO URL with `SAMLRequest` + CSRF `RelayState`.
3. IdP POSTs to `POST /api/v1/auth/saml/acs` with `SAMLResponse` + `RelayState`.
4. Backend validates the assertion (Spring Security OpenSAML), **links or creates** a local `identity.users` row, stores `identity.user_identities` (`provider=saml`), issues JWTs.
5. Browser is redirected to `AGENTBENCH_SAML_SUCCESS_REDIRECT_URL` with tokens in the **URL fragment** (same pattern as OIDC).

API / CI alternative: after `/login`, call  
`POST /api/v1/auth/saml/token` with `{"samlResponse","relayState"}` → `TokenResponse`.

SP metadata for IdP registration: `GET /api/v1/auth/saml/metadata`.

## Enable SP (Compose / `.env`)

```bash
cp .env.example .env
```

```bash
AGENTBENCH_SAML_ENABLED=true
AGENTBENCH_SAML_ENTITY_ID=http://localhost:8080/saml2/metadata
AGENTBENCH_SAML_ACS_URL=http://localhost:8080/api/v1/auth/saml/acs
AGENTBENCH_SAML_SUCCESS_REDIRECT_URL=http://localhost:3000/
```

Optional **env/props fallback IdP** (used only when no enabled DB IdPs exist):

```bash
AGENTBENCH_SAML_METADATA_URL=https://idp.example.com/metadata
AGENTBENCH_SAML_DISPLAY_NAME=Enterprise SSO
AGENTBENCH_SAML_ATTR_EMAIL=email
AGENTBENCH_SAML_ATTR_DISPLAY_NAME=displayName
# Or inline:
# AGENTBENCH_SAML_METADATA_XML='<?xml version="1.0"?>...'
```

Leave `AGENTBENCH_SAML_ENABLED=false` (default) for local demos; password + optional OIDC stay available.

At the IdP, register:

- **SP Entity ID** = `AGENTBENCH_SAML_ENTITY_ID`
- **ACS URL** = `AGENTBENCH_SAML_ACS_URL` (HTTP-POST)
- Allow **unsigned AuthnRequests** (AgentBench does not ship SP signing keys in this milestone)
- Release email (and optionally display name) attributes matching the attribute map

## Manage IdPs (admin)

OWNER/ADMIN manage org-scoped IdPs in the UI (**SSO** tab) or via API:

| Method | Path | Purpose |
|---|---|---|
| `GET` | `/api/v1/orgs/{orgId}/saml/idps` | List |
| `POST` | `/api/v1/orgs/{orgId}/saml/idps` | Add (metadata URL and/or XML, attribute map, enabled) |
| `PUT` | `/api/v1/orgs/{orgId}/saml/idps/{idpId}` | Edit / disable |
| `DELETE` | `/api/v1/orgs/{orgId}/saml/idps/{idpId}` | Remove |

`registrationId` is globally unique (kebab-case) and becomes the login path segment. Creating an IdP loads metadata immediately (URL via HTTP or inline XML) so bad metadata fails fast.

Schema: Flyway `V16__saml_multi_idp.sql` → `identity.saml_identity_providers` (RLS org isolation).

## Account linking

| Situation | Result |
|---|---|
| First SAML login | Creates local user (`password_hash` null) + identity row |
| Same SAML NameID again (same IdP) | Reuses linked user |
| SAML email matches existing password user | Links identity; password login still works |
| DB IdP subjects | Stored as `registrationId:NameID` under `provider=saml` |
| Env fallback subjects | Stored as NameID (unchanged) |
| Provider / SP not configured | `/login`, ACS, and UI buttons unavailable |

## Residual limits

- AuthnRequests are **unsigned**; IdPs that require signed AuthnRequests need a follow-up (SP signing credential config).
- Assertion **encryption** is not claimed; configure the IdP to send signed (optionally unencrypted) assertions the SP can validate from metadata certs.

## Tests

- `SamlAuthIntegrationTest` — env/props single IdP via WireMock metadata + signed assertions.
- `SamlMultiIdpAdminIntegrationTest` — OWNER/ADMIN CRUD + authz (VIEWER/DEVELOPER denied) + DB IdP login via WireMock.

CI does **not** need a live IdP.
