# AgentBench — SAML 2.0 enterprise SSO

Local email/password JWT remains the default. Optional **Google/GitHub OIDC** stays unchanged (`docs/AUTH_OIDC.md`). This document covers the optional **SAML 2.0 Service Provider** path for enterprise IdPs (Okta, Azure AD, ADFS, etc.), including **org-scoped multi-IdP** admin APIs/UI and optional **SP signing** for AuthnRequests.

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
| Signed AuthnRequests / SP signing keys | **Landed** — optional; default off; HTTP-Redirect `SigAlg`/`Signature` + SP metadata cert |
| Encrypted assertions | **Not claimed** — signed assertions expected |

## How it works

1. Frontend loads `GET /api/v1/auth/providers` and shows **Continue with …** for each enabled SAML IdP (`type=saml`).
   - If any **enabled** DB IdPs exist → those are listed (`/api/v1/auth/saml/login/{registrationId}`).
   - If none → env/props single IdP is listed when configured (`/api/v1/auth/saml/login`).
2. Browser hits the authorize path → 302 to the IdP SSO URL with `SAMLRequest` + CSRF `RelayState` (and `SigAlg`/`Signature` when SP signing is enabled).
3. IdP POSTs to `POST /api/v1/auth/saml/acs` with `SAMLResponse` + `RelayState`.
4. Backend validates the assertion (Spring Security OpenSAML), **links or creates** a local `identity.users` row, stores `identity.user_identities` (`provider=saml`), issues JWTs.
5. Browser is redirected to `AGENTBENCH_SAML_SUCCESS_REDIRECT_URL` with tokens in the **URL fragment** (same pattern as OIDC).

API / CI alternative: after `/login`, call  
`POST /api/v1/auth/saml/token` with `{"samlResponse","relayState"}` → `TokenResponse`.

SP metadata for IdP registration: `GET /api/v1/auth/saml/metadata` (includes signing cert + `AuthnRequestsSigned="true"` when signing is on).

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

### Optional SP signing (AuthnRequests)

Default **off** so local Compose demos do not require keys or IdP signature verification. When an IdP **requires** signed AuthnRequests:

```bash
AGENTBENCH_SAML_SIGNING_ENABLED=true
AGENTBENCH_SAML_SIGNING_PRIVATE_KEY_PATH=/run/secrets/saml-sp-key.pem
AGENTBENCH_SAML_SIGNING_CERTIFICATE_PATH=/run/secrets/saml-sp-cert.pem
# Or inline PEM (escape newlines as \n):
# AGENTBENCH_SAML_SIGNING_PRIVATE_KEY='-----BEGIN PRIVATE KEY-----\n...'
# AGENTBENCH_SAML_SIGNING_CERTIFICATE='-----BEGIN CERTIFICATE-----\n...'
```

Generate a platform SP keypair (PKCS#8 + self-signed cert example):

```bash
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out saml-sp-key.pem
openssl req -new -x509 -key saml-sp-key.pem -out saml-sp-cert.pem -days 825 -subj "/CN=agentbench-sp"
```

Signing is **platform-level** (one SP entity ID → one credential for all IdPs). Per-IdP admin rows still supply IdP metadata only.

When signing is on:

- Login redirects include HTTP-Redirect binding `SigAlg` + `Signature` (RSA-SHA256) over `SAMLRequest`/`RelayState`/`SigAlg`
- `GET /api/v1/auth/saml/metadata` sets `AuthnRequestsSigned="true"` and publishes a `KeyDescriptor use="signing"`
- Misconfigured keys (enabled but unloadable) fail login/metadata with a clear validation error

When signing is off (default): AuthnRequests stay unsigned — IdP must allow unsigned requests (common for lab IdPs).

At the IdP, register:

- **SP Entity ID** = `AGENTBENCH_SAML_ENTITY_ID`
- **ACS URL** = `AGENTBENCH_SAML_ACS_URL` (HTTP-POST)
- Import SP metadata from `GET /api/v1/auth/saml/metadata` (or paste the signing cert when signing is on)
- If signing is **off**: allow unsigned AuthnRequests
- If signing is **on**: require/verify signed AuthnRequests using the SP cert from metadata
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

- Assertion **encryption** is not claimed; configure the IdP to send signed (optionally unencrypted) assertions the SP can validate from metadata certs.
- SP signing keys are platform config/secrets (not per-IdP admin fields); rotate by updating PEM mounts/env and restarting.

## Tests

- `SamlAuthIntegrationTest` — env/props single IdP via WireMock metadata + signed assertions (unsigned AuthnRequests).
- `SamlMultiIdpAdminIntegrationTest` — OWNER/ADMIN CRUD + authz (VIEWER/DEVELOPER denied) + DB IdP login via WireMock.
- `SamlAuthnRequestFactoryTest` — unsigned redirect + signed redirect with verifiable `Signature`.
- `SamlSpSigningIntegrationTest` — signing enabled via PEM paths; login redirect signature verifies; SP metadata includes cert.

CI does **not** need a live IdP.
