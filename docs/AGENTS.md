# Agent providers

Grok is the default provider for evaluation runs and any other agent call that needs a model. Pick another adapter only when you want a different provider.

Which host is called depends on which key is set.

| Key that is set | Endpoint |
|---|---|
| Org secret ref, or `XAI_API_KEY` | `POST https://api.x.ai/v1/chat/completions` (OpenAI-compatible). `XAI_BASE_URL` can override the base. |
| `XAI_API_KEY` empty, no secret ref, `CURSOR_API_KEY` set | `POST https://api.cursor.com/v1/agents` with `model.id` set to the Grok model. Auth is HTTP Basic: the legacy Cursor user API key is the username and the password is empty. The reply is read from `GET https://api.cursor.com/v1/agents/{agentId}/runs/{runId}` (`result`). |

`XAI_API_KEY` wins when both environment variables are set. A legacy Cursor user API key is not an xAI secret and is never sent to `https://api.x.ai/v1`.

| | |
|---|---|
| Adapter | `GROK` |
| Default model | `grok-4.7` (`GROK_MODEL`) |
| API key | Org secret ref, else `XAI_API_KEY`, else `CURSOR_API_KEY` |

## Legacy Cursor API key

A legacy Cursor user API key (Cursor Dashboard → API Keys) belongs in `CURSOR_API_KEY`. AgentBench reads it only when `XAI_API_KEY` is empty and the agent has no org secret ref.

On a legacy Cursor plan that key's included Grok models are:

| Model id | Role |
|---|---|
| `grok-4.7` | Default. Passed as `model.id` on `POST https://api.cursor.com/v1/agents`. Also the flagship model the xAI API serves. |
| `grok-4.6` | Set `GROK_MODEL=grok-4.6` or the agent `config.model`. |
| `grok-4.5` | Same override. |

Do not commit either key, do not put one in config JSON, and do not log it. Store a per-org xAI key with the existing secret ref (`POST /api/v1/orgs/{orgId}/secrets`, purpose `AGENT`) and pass `secretRefId` on the agent. A secret ref is sent to `https://api.x.ai/v1`, not to the Cursor agents API.

If a run needs a model and none of those credentials is set, the call fails with `AGENT_CREDENTIAL_MISSING` and the message names `XAI_API_KEY` and `CURSOR_API_KEY`.

## When a run uses Grok

- Registering an agent with `adapterType` omitted stores `GROK`.
- Creating an evaluation with `agentId` omitted uses the org's enabled agent named `Grok`, creating it if needed.
- The UI adapter list and the evaluation form both start on that default.
- An explicit `FAKE`, `OPENAI_COMPATIBLE`, `HTTP`, or `CODING_CLI` agent is left alone.

`FAKE` stays available for local demos and CI when no key is configured. It does not call a model.

## Other adapters

| Adapter | When |
|---|---|
| `FAKE` | Deterministic patch. No key. Local demos and CI. |
| `OPENAI_COMPATIBLE` | Some other OpenAI-compatible host. Requires an org secret ref. Default base URL stays `https://api.openai.com`. |
| `HTTP` | Webhook agent. |
| `CODING_CLI` | Isolator-backed CLI. See [AGENTS_CODING_CLI.md](AGENTS_CODING_CLI.md). |
| `SPRING_AI` | Accepted at the API and stored as the legacy label; invocations use `OPENAI_COMPATIBLE`. |

Per-agent `config.model` overrides `GROK_MODEL` on both hosts. Per-agent `config.baseUrl` overrides only the xAI base URL (used by tests; production stays on `https://api.x.ai/v1`). `CURSOR_AGENTS_URL` overrides the Cursor create URL (default `https://api.cursor.com/v1/agents`).
