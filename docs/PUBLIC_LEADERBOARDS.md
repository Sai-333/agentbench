# AgentBench — Public leaderboards & signed embeds

Org leaderboards stay **private by default**. OWNER/ADMIN may publish a sanitized snapshot under a stable slug. Signed embeds and Cache-Control headers make portfolio “public boards / embeds / CDN-friendly caching” honest without requiring a real CDN.

Published boards also support **anonymous-capable comments and up/down votes** with rate limits, size limits, and OWNER/ADMIN soft-delete moderation. Org-private (unpublished) boards never expose these write surfaces.

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

## Comments (anonymous or authenticated)

Only **currently published** boards accept reads/writes. Unpublished / never-published → `404`.

```http
GET  /api/v1/public/leaderboards/{slug}/comments?page=0&size=50
POST /api/v1/public/leaderboards/{slug}/comments
{"body":"Nice results","displayName":"Guest"}
```

- Auth is optional. When a Bearer JWT is present, the comment is marked `authenticatedAuthor: true` and attributed to that user id in storage (not exposed publicly beyond the flag).
- `displayName` is optional (1–80 chars, letters/numbers/spaces/`. _ ' -`). Defaults to email local-part when signed in, else `Anonymous`.
- Body: 1–2000 characters (configurable).
- Soft moderation (OWNER/ADMIN):

```http
DELETE /api/v1/orgs/{orgId}/benchmark-versions/{versionId}/leaderboard/comments/{commentId}
Authorization: Bearer <access>
```

Soft-deleted comments disappear from public lists; VIEWER cannot delete.

## Votes (board-level up / down)

MVP is **board-level** up (`+1`) / down (`-1`), not per-row stars.

```http
GET  /api/v1/public/leaderboards/{slug}/votes?voterFingerprint=<fp>
POST /api/v1/public/leaderboards/{slug}/votes
{"value":1,"voterFingerprint":"client-stable-id"}
```

- Authenticated voters use `user:<userId>` as the durable key (fingerprint ignored).
- Anonymous voters must send `voterFingerprint` (8–64 chars `[a-zA-Z0-9_-]`); UI stores one in `localStorage`.
- Upsert: changing value updates the same voter row. Response includes `upCount`, `downCount`, `score`, and `myVote`.

## Abuse basics

| Control | Default |
|---|---|
| Comment body max | 2000 chars |
| Display name max | 80 chars |
| Comment rate / IP | 5 / 600s |
| Vote rate / IP | 30 / 600s |
| Moderation | OWNER/ADMIN soft-delete |

Rate limiting is **in-process** (single API replica / Compose). Not a distributed edge WAF.

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
- HTML embeds show **read-only** vote tallies + recent comments and link to the public page for posting.

Dashboard **Copy embed snippet** mints a token and copies an absolute iframe tag.

## Cache headers (CDN-friendly, no CDN required)

Public board and embed GETs return:

- `Cache-Control: public, max-age=60, stale-while-revalidate=300` (configurable)
- weak `ETag` derived from slug / publishedAt / page fingerprint

Comment/vote GETs are live (no long Cache-Control). Any reverse proxy or CDN can honor board Cache-Control. A managed CDN is **not** claimed.

Config (`application.yml` / env):

| Property / env | Default |
|---|---|
| `agentbench.public-leaderboards.embed-secret` / `AGENTBENCH_PUBLIC_EMBED_SECRET` | (JWT secret) |
| `default-embed-ttl-seconds` / `AGENTBENCH_PUBLIC_EMBED_TTL_SECONDS` | `604800` (7d) |
| `max-embed-ttl-seconds` / `AGENTBENCH_PUBLIC_EMBED_MAX_TTL_SECONDS` | `7776000` (90d) |
| `cache-max-age-seconds` / `AGENTBENCH_PUBLIC_CACHE_MAX_AGE_SECONDS` | `60` |
| `cache-stale-while-revalidate-seconds` / `AGENTBENCH_PUBLIC_CACHE_SWR_SECONDS` | `300` |
| `max-comment-body-length` / `AGENTBENCH_PUBLIC_COMMENT_MAX_LEN` | `2000` |
| `comment-rate-limit` / `AGENTBENCH_PUBLIC_COMMENT_RATE_LIMIT` | `5` |
| `vote-rate-limit` / `AGENTBENCH_PUBLIC_VOTE_RATE_LIMIT` | `30` |

## Storage

Flyway `V21__public_leaderboard_comments_votes.sql`:

- `analytics.leaderboard_comments` (soft-delete columns)
- `analytics.leaderboard_votes` (unique per publication + voter_key)
- Postgres RLS tenant isolation (writes require session org GUC)

## Not claimed

- Full CDN product / edge network
- Distributed / multi-replica rate limiting or full anti-spam / CAPTCHA / ML moderation
- Per-entry (agent/task row) votes or star ratings
- Comments/votes on **org-private** (unpublished) boards
- Full service-mesh mTLS / AMQPS / live AWS Compliance Object Lock demo bucket / Oracle Always Free hosting
