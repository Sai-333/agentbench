# Suite runners (Java / Python / Node)

AgentBench evaluates coding agents with **visible** and **hidden** test suites. Suite execution is selected by the suite `framework` string and runs through `SuiteExecutionService`:

1. Inline / pre-seeded JUnit XML ingest (unchanged)
2. Isolator-backed **toolchain runners** (`SuiteToolchainRunner`) when framework matches
3. Fixture-v1 file checks (CI demos)

All paths return the same `CaseOutcome` model. **Visible** scoring still computes F2P / P2P / P2F / F2F on comparable baseline↔post identities. **Hidden** suites remain **post-only pass rate** — never labeled F2P.

## Supported toolchains

| Framework | Runner id | Default command | Reports dir | Image config |
|---|---|---|---|---|
| `fixture-v1` | `suite-v1` | (in-process checks) | — | — |
| `junit` / `maven` / `surefire` | `maven-junit` | `mvn -B -q test -Dmaven.repo.local=/tmp/m2` | `target/surefire-reports` | `AGENTBENCH_JAVA_RUNNER_IMAGE` |
| `pytest` / `python` | `pytest` | `pytest --junitxml=reports/junit.xml -q` | `reports` | `AGENTBENCH_PYTHON_RUNNER_IMAGE` |
| `jest` / `node` / `nodejs` | `jest` | `jest --ci` + `jest-junit` reporter | `reports` | `AGENTBENCH_NODE_RUNNER_IMAGE` |

Suite `entryCommand` overrides the default when non-blank (whitespace-split, or `sh -c` when shell metacharacters are present). Unsafe fragments (`docker.sock`, `--privileged`) are rejected.

## Isolation

Toolchain commands run via `ExecutionIsolatorService` → active `RuntimeIsolator` (Docker hardened by default; optional gVisor / Firecracker; `local` for CI). Per-run `imageOverride` selects the language image. Network is **off** by default — runner images must ship their tools (see `deploy/runners/`).

Task JSON may set `evalConfig.networkEnabled: true` (or suite `networkEnabled`) when a trusted demo needs egress; leave off for untrusted patches.

## Hidden suites

Hidden execution still:

- Requires a sealed patch
- Uses a separate workspace clone + hidden fixture mount (`ExecutionStage.HIDDEN_TESTS`)
- Never writes F2P/P2P/P2F/F2F transitions

For pytest/Jest, optional `fixtureContent.workspace` files are overlaid into the hidden-run clone so tests can stay out of the agent-visible tree.

## Build runner images

```bash
docker build -t agentbench/runner-python:3.12 deploy/runners/python
docker build -t agentbench/runner-node:22 deploy/runners/node
# or: docker compose --profile build-runners build
```

Java uses the public Maven Temurin image by default (no custom Dockerfile required).

## Residuals / non-claims

- Docker-only isolation is **not** certified hostile multi-tenant SaaS security
- Custom python/node images must be built/pulled on the worker host before those frameworks run under Docker mode
- Quality analysis (Checkstyle/SpotBugs/PMD) remains Java-oriented; no Python/Node quality scanners in this milestone
- Artifact TTL/GC, secret scanning, and flake quarantine are out of scope here
