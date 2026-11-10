# Suite runner images

Language toolchains invoke tests inside `RuntimeIsolator` with a per-language image override.

| Toolchain | Framework strings | Default image | Dockerfile | Also used for |
|---|---|---|---|---|
| Maven / JUnit 5 | `junit`, `maven`, `surefire` | `maven:3.9.9-eclipse-temurin-21-alpine` | (public) | — |
| pytest | `pytest`, `python` | `agentbench/runner-python:3.12` | `python/` | optional **ruff** quality scan |
| Jest | `jest`, `node`, `nodejs` | `agentbench/runner-node:22` | `node/` | optional **eslint** quality scan |

Build the custom images (once per host):

```bash
docker build -t agentbench/runner-python:3.12 deploy/runners/python
docker build -t agentbench/runner-node:22 deploy/runners/node
```

Or via Compose profile:

```bash
docker compose --profile build-runners build
```

Env knobs (worker):

- `AGENTBENCH_JAVA_RUNNER_IMAGE`
- `AGENTBENCH_PYTHON_RUNNER_IMAGE`
- `AGENTBENCH_NODE_RUNNER_IMAGE`
- `AGENTBENCH_RUNNER_IMAGE` — default / CODING_CLI image (unchanged)

**Honesty:** images are trusted controller inputs; Docker isolation remains shared-kernel. Prefer gVisor/Firecracker on Linux for stronger sandboxes (ADR-004). Hidden suites still score post-only pass rate — never F2P. Optional language quality scanners (ruff/eslint) default off — see `docs/QUALITY_LANGUAGE.md`.
