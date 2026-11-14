# Agent providers

Grok is the default provider for evaluation runs and any other agent call that needs a model. Pick another adapter only when you want a different provider.

The Grok call is OpenAI-compatible Chat Completions.

| | |
|---|---|
| Adapter | `GROK` |
| Base URL | `https://api.x.ai/v1` (`XAI_BASE_URL` can override it) |
| Default model | `grok-4.7` (`GROK_MODEL`) |
| API key | Org secret ref, else `XAI_API_KEY`, else `CURSOR_API_KEY` |

## Legacy Cursor API key

A legacy Cursor user API key (Cursor Dashboard → API Keys) belongs in `CURSOR_API_KEY`. AgentBench reads it only when `XAI_API_KEY` is empty and the agent has no org secret ref.

On a legacy Cursor plan that key's included Grok models are:

| Model id | Role |
|---|---|
| `grok-4.7` | Default. Also the flagship model the xAI API serves for code and chat. |
| `grok-4.6` | Previous flagship. Set `GROK_MODEL=grok-4.6` or the agent `config.model`. |
| `grok-4.5` | Earlier Grok in the same pool. Same override. |

`XAI_API_KEY` is the direct key for `https://api.x.ai/v1` and wins when both variables are set. Do not commit either key, do not put one in config JSON, and do not log it. Store a per-org key with the existing secret ref (`POST /api/v1/orgs/{orgId}/secrets`, purpose `AGENT`) and pass `secretRefId` on the agent. A secret ref overrides both environment variables.

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

Per-agent `config.model` overrides `GROK_MODEL`. Per-agent `config.baseUrl` overrides the xAI base URL (used by tests; production stays on `https://api.x.ai/v1`).
