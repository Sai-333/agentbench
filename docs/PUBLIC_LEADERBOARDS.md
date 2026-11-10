# AgentBench — Public leaderboards & signed embeds

Org leaderboards stay **private by default**. OWNER/ADMIN may publish a sanitized snapshot under a stable slug. Signed embeds and Cache-Control headers make portfolio “public boards / embeds / CDN-friendly caching” honest without requiring a real CDN or anonymous writes.

## Publish / unpublish

```http
POST /api/v1/orgs/{orgId}/benchmark-versions/{versionId}/leaderboard/publish
Authorization: Bearer <access>
{"publicSlug":"my-org-bench-1-0-0","title":"Optional title"}

POST /api/v1/orgs/{orgId}/benchmark-versions/{versionId}/leaderboard/unpublish
```

Unauthenticated live query:

```http
GET /api/v1/public/leaderboards/{slug}
```

UI page: `/public/leaderboards/{slug}`.

Public payloads omit org/agent/evaluation UUIDs, tokens, costs, and secrets.

## Signed embeds (HMAC)

OWNER/ADMIN mint a time-limited HMAC token for a **currently published** board:

```http
POST /api/v1/orgs/{orgId}/benchmark-versions/{versionId}/leaderboard/embed-token
Authorization: Bearer <access>
{"ttlSeconds":604800}
```

Response includes `token`, `expiresAt`, `embedJsonPath`, `embedHtmlPath`, plus ready `iframeSnippet` / `scriptSnippet`.

### Embed reads

```http
GET /api/v1/public/leaderboards/{slug}/embed?token=<v1.payload.sig>
GET /api/v1/public/leaderboards/{slug}/embed?token=...&format=html
```

- Token format: `v1.<base64url(json)>.<base64url(hmac-sha256)>` over `slug` / `iat` / `exp`.
- Secret: `AGENTBENCH_PUBLIC_EMBED_SECRET` (or JWT secret fallback). Must be ≥ 32 characters.
- Rejects missing, malformed, tampered, expired, or slug-mismatched tokens (401).
- HTML responses set `Content-Security-Policy: frame-ancestors *` so portfolios can iframe them.

Dashboard **Copy embed snippet** mints a token and copies an absolute iframe tag.

## Cache headers (CDN-friendly, no CDN required)

Public and embed GETs return:

- `Cache-Control: public, max-age=60, stale-while-revalidate=300` (configurable)
- weak `ETag` derived from slug / publishedAt / page fingerprint

Any reverse proxy or CDN can honor these. A managed CDN is **not** claimed.

Config (`application.yml` / env):

| Property / env | Default |
|---|---|
| `agentbench.public-leaderboards.embed-secret` / `AGENTBENCH_PUBLIC_EMBED_SECRET` | (JWT secret) |
| `default-embed-ttl-seconds` / `AGENTBENCH_PUBLIC_EMBED_TTL_SECONDS` | `604800` (7d) |
| `max-embed-ttl-seconds` / `AGENTBENCH_PUBLIC_EMBED_MAX_TTL_SECONDS` | `7776000` (90d) |
| `cache-max-age-seconds` / `AGENTBENCH_PUBLIC_CACHE_MAX_AGE_SECONDS` | `60` |
| `cache-stale-while-revalidate-seconds` / `AGENTBENCH_PUBLIC_CACHE_SWR_SECONDS` | `300` |

## Not claimed

- Full CDN product / edge network
- Anonymous votes, comments, or other public writes
- Multi-IdP SAML admin UI / full service-mesh mTLS / AWS Object Lock WORM / Oracle Always Free hosting
