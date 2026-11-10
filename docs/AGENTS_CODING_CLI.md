# CODING_CLI agent adapter

Provider-independent coding-agent CLI path. Invocations run inside `RuntimeIsolator`
(Docker hardened by default, optional gVisor, local CI fallback) — **not** a raw host shell.

Default demos stay on **FAKE**. Use CODING_CLI when you want an isolator-backed CLI agent.

## Enable / register

1. Ensure the worker can reach the isolator backend (`AGENTBENCH_EXECUTION_MODE=docker|gvisor|firecracker|local`).
2. Create an agent with `adapterType: CODING_CLI`.
3. Configure either:
   - **Fixture mode (CI / demos):** `config.patch` = unified diff → no process spawn.
   - **Isolator mode:** `binary` / `argv` / `commandTemplate` (+ optional `image`).

Example (fixture):

```json
{
  "name": "cli-fixture",
  "adapterType": "CODING_CLI",
  "config": {
    "model": "fixture",
    "patch": "diff --git a/README.md b/README.md\n--- a/README.md\n+++ b/README.md\n@@ -1 +1,2 @@\n AgentBench evaluation workspace\n+CLI fixture\n"
  }
}
```

Example (isolator — stock runner image, no host `coding-agent` binary required):

```json
{
  "name": "cli-echo-patch",
  "adapterType": "CODING_CLI",
  "config": {
    "model": "echo",
    "timeoutSec": 60,
    "argv": [
      "sh",
      "-c",
      "printf 'diff --git a/README.md b/README.md\\n--- a/README.md\\n+++ b/README.md\\n@@ -1 +1,2 @@\\n hello\\n+from-cli\\n' > .agentbench/patch.diff"
    ]
  }
}
```

Example (real CLI image):

```json
{
  "name": "my-coding-agent",
  "adapterType": "CODING_CLI",
  "config": {
    "image": "ghcr.io/example/coding-agent:1.2.3",
    "binary": "coding-agent",
    "args": ["--prompt-file", "{{promptFile}}", "--out", "{{patchFile}}"],
    "timeoutSec": 120,
    "networkEnabled": false,
    "memoryMb": 1024
  }
}
```

## Config keys

| Key | Meaning |
|---|---|
| `patch` | Fixture unified diff (skips isolator spawn) |
| `argv` | Preferred JSON string array (placeholders expanded) |
| `binary` / `command` | Executable when `argv` omitted |
| `args` | Extra argv after binary |
| `commandTemplate` | Whitespace-split template (prefer `argv` for spaces) |
| `image` | Optional container image override |
| `patchFile` | Workspace-relative output path (default `.agentbench/patch.diff`) |
| `timeoutSec` | Per-call timeout (also respects org/agent defaults via invocation service) |
| `networkEnabled` / `memoryMb` / `cpuLimit` / `model` | Isolator / metadata knobs |

**Placeholders:** `{{prompt}}`, `{{promptFile}}`, `{{contextFile}}`, `{{patchFile}}`,
`{{workspace}}`, `{{evaluationId}}`, `{{taskId}}`, `{{orgId}}`, `{{attempt}}`.

Before invoke, the provider stages `.agentbench/prompt.txt` and `.agentbench/context.json`
in the evaluation workspace. Env vars `AGENTBENCH_*_FILE` / ids are injected into the isolator.

Patch capture order: `patchFile` contents → unified diff / fenced diff on stdout.

## Global defaults (env)

| Env | Default |
|---|---|
| `AGENTBENCH_CODING_CLI_BINARY` | `coding-agent` |
| `AGENTBENCH_CODING_CLI_PATCH_FILE` | `.agentbench/patch.diff` |
| `AGENTBENCH_CODING_CLI_TIMEOUT_SEC` | `60` |
| `AGENTBENCH_CODING_CLI_NETWORK` | `false` |

## Residual risks (honest)

- Isolation equals the active `RuntimeIsolator` (Docker shared-kernel residual by default;
  prefer `AGENTBENCH_EXECUTION_MODE=gvisor` on Linux with `runsc`, or `firecracker` on Linux+KVM).
- **Not claimed:** certified hostile multi-tenant SaaS / arbitrary untrusted public CLI images without ops review.
- Live coding-agent binaries are **not** required in CI; unit tests mock the isolator; optional Docker smoke uses `sh` in the stock runner image and is skipped when Docker is absent.
