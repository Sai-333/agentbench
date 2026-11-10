# AgentBench — OIDC / OAuth login (Google + GitHub)

Local email/password JWT remains the default and always works. Google and GitHub are **optional** authorization-code providers that issue the **same** access + refresh JWT pair after linking or creating a local user.

## How it works

1. Frontend loads `GET /api/v1/auth/providers` and shows **Continue with Google/GitHub** only for providers that are enabled **and** have client id/secret/redirect URI set.
2. Browser hits `GET /api/v1/auth/oidc/{provider}/authorize` → 302 to the IdP (with CSRF `state`).
3. IdP redirects to `GET /api/v1/auth/oidc/{provider}/callback?code=…&state=…`.
4. Backend exchanges the code at the IdP token endpoint, loads the profile, **links or creates** a local `identity.users` row (`password_hash` may stay null), stores `identity.user_identities`, issues JWTs.
5. Browser is redirected to `AGENTBENCH_OIDC_SUCCESS_REDIRECT_URL` with tokens in the **URL fragment** (`#access_token=…&refresh_token=…`). The SPA stores them and clears the fragment.

API / CI alternative (no browser): after `/authorize`, call  
`POST /api/v1/auth/oidc/{provider}/token` with `{"code","state"}` → `TokenResponse`.

Client secrets and access tokens are **never logged**.

## Register OAuth apps (Sai)

### Google

1. Open [Google Cloud Console → APIs & Services → Credentials](https://console.cloud.google.com/apis/credentials).
2. Create an **OAuth 2.0 Client ID** (Web application).
3. Authorized redirect URI (local Compose via frontend proxy):

   `http://localhost:3000/api/v1/auth/oidc/google/callback`

   Optional direct-backend URI: `http://localhost:8080/api/v1/auth/oidc/google/callback` (must match env).
4. Copy **Client ID** and **Client secret**.
5. OAuth consent screen: add your Google account as a test user while the app is in Testing.

### GitHub

1. GitHub → Settings → Developer settings → **OAuth Apps** → New OAuth App  
   (or Organization settings → Developer settings for an org app).
2. Homepage URL: `http://localhost:3000`
3. Authorization callback URL:

   `http://localhost:3000/api/v1/auth/oidc/github/callback`
4. Create the app, then **Generate a new client secret**.
5. Copy Client ID + client secret. Scopes used: `read:user user:email`.

## Local Compose env

```bash
cp .env.example .env
```

Set in `.env` (never commit real secrets):

```bash
AGENTBENCH_OIDC_SUCCESS_REDIRECT_URL=http://localhost:3000/

AGENTBENCH_OIDC_GOOGLE_ENABLED=true
AGENTBENCH_OIDC_GOOGLE_CLIENT_ID=your-google-client-id
AGENTBENCH_OIDC_GOOGLE_CLIENT_SECRET=your-google-client-secret
AGENTBENCH_OIDC_GOOGLE_REDIRECT_URI=http://localhost:3000/api/v1/auth/oidc/google/callback

AGENTBENCH_OIDC_GITHUB_ENABLED=true
AGENTBENCH_OIDC_GITHUB_CLIENT_ID=your-github-client-id
AGENTBENCH_OIDC_GITHUB_CLIENT_SECRET=your-github-client-secret
AGENTBENCH_OIDC_GITHUB_REDIRECT_URI=http://localhost:3000/api/v1/auth/oidc/github/callback
```

Then:

```bash
docker compose up -d --build
curl -sf http://localhost:8080/api/v1/auth/providers
# open http://localhost:3000 — OIDC buttons appear when configured
```

Leave `*_ENABLED=false` (default) to hide the buttons; password login stays available.

Token/userinfo endpoint URLs default to Google/GitHub production hosts and are overridable (`AGENTBENCH_OIDC_*_TOKEN_URI`, `*_USERINFO_URI`, …) for tests or enterprise IdP mirrors.

## Account linking

| Situation | Result |
|---|---|
| First OIDC login | Creates local user (`password_hash` null) + identity row |
| Same IdP subject again | Reuses linked user |
| OIDC email matches existing password user | Links identity; password login still works |
| Provider not configured | `/authorize` and buttons unavailable |

## Security notes

- `state` is one-time, stored in Postgres, ~10 minute TTL.
- Prefer HTTPS redirect URIs outside local demos.
- Rotate client secrets in the IdP console and update `.env` / K8s secrets; restart backend/worker.
