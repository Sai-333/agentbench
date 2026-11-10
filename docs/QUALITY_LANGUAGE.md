# AgentBench — Optional Python / Node quality scanners

Checkstyle, SpotBugs, and PMD remain the **primary** local quality tools for Java workspaces. Optional **ruff** (Python) and **eslint** (Node) scanners can contribute findings to the same quality stage. They are **not** the sole scoring authority (neither is Sonar).

## Default (local Compose / CI)

Both language scanners are **off**. No ruff/eslint install is required for demos or unit tests.

```yaml
agentbench.quality.python.enabled: false   # AGENTBENCH_QUALITY_PYTHON_ENABLED=false
agentbench.quality.node.enabled: false     # AGENTBENCH_QUALITY_NODE_ENABLED=false
```

When disabled, metrics include `ruff` / `eslint` nodes with `stub=true` and notes `python_quality_disabled` / `node_quality_disabled`. Primary Java tools continue unchanged.

## How it works

For each evaluation quality stage (baseline workspace + post-patch workspace):

1. **Primary Java path** (always): Checkstyle / SpotBugs / PMD XML under `target/`, else builtin Java heuristics.
2. **Optional Sonar** (separate; see `docs/QUALITY_SONAR.md`).
3. **Optional Python (ruff)** when enabled and `.py` / `.pyi` sources exist:
   - Prefer workspace JSON import (ruff `--output-format=json` shape) at:
     - `.agentbench/ruff.json`
     - `ruff-report.json`
     - `reports/ruff.json`
     - `target/ruff.json`
   - Else optionally invoke `ruff check --output-format=json --exit-zero .` via `RuntimeIsolator` (`ExecutionStage.QUALITY`) using `AGENTBENCH_PYTHON_RUNNER_IMAGE`.
   - Else builtin Python heuristics (print / bare `except` / `eval` / star-import / TODO) when `allow-builtin-heuristics=true`.
4. **Optional Node (eslint)** when enabled and `.js` / `.jsx` / `.mjs` / `.cjs` / `.ts` / `.tsx` sources exist:
   - Prefer workspace JSON import (eslint `-f json` shape) at:
     - `.agentbench/eslint.json`
     - `eslint-report.json`
     - `reports/eslint.json`
     - `target/eslint.json`
   - Else optionally invoke eslint via `RuntimeIsolator` on the node runner image (bundled flat config at `/opt/agentbench-runner/eslint.config.mjs`).
   - Else builtin Node heuristics (`console` / `eval` / `var` / loose `==` / TODO).

Findings are fingerprinted and diffed baseline↔post into **new** vs **pre-existing**, same as the Java tools. Real (non-stub) language findings contribute to the aggregate `code_quality` normalized score. The tool label becomes e.g. `checkstyle+spotbugs+pmd+ruff+eslint` when those scanners contribute.

Workspaces without matching sources get an explicit stub (`no_python_sources` / `no_node_sources`) — not a fake clean score.

## Enable for Compose / deploy

```bash
# .env (worker runs the quality stage)
AGENTBENCH_QUALITY_PYTHON_ENABLED=true
AGENTBENCH_QUALITY_NODE_ENABLED=true
# Optional knobs:
# AGENTBENCH_QUALITY_PYTHON_TIMEOUT_SEC=60
# AGENTBENCH_QUALITY_NODE_TIMEOUT_SEC=60
# AGENTBENCH_QUALITY_PYTHON_BUILTIN=true
# AGENTBENCH_QUALITY_NODE_BUILTIN=true
# AGENTBENCH_QUALITY_PYTHON_ISOLATOR=true
# AGENTBENCH_QUALITY_NODE_ISOLATOR=true
# AGENTBENCH_QUALITY_PYTHON_COMMAND=   # override argv / sh -c string
# AGENTBENCH_QUALITY_NODE_COMMAND=
```

Rebuild / pull runner images so isolator invokes have the CLIs:

```bash
docker compose --profile build-runners build
# python image includes ruff; node image includes eslint + default config
```

### Offline / eval-friendly import

For honest new-vs-pre-existing scoring without Docker images, drop ruff/eslint JSON into each workspace (baseline and patched) before the quality stage. CI unit tests use fixtures under the test workspace trees.

## Honesty

| Claim | Status |
|---|---|
| Opt-in ruff / eslint scanners | Done when enabled |
| Checkstyle / SpotBugs / PMD primary for Java | Unchanged |
| Sonar as sole scoring authority | **Not claimed** |
| Language scanners as sole scoring authority | **Not claimed** |
| Builtin heuristics equal full ruff/eslint | **Not claimed** — heuristics are a fallback observation path |
| Runner images always present on Mac Compose | **Residual** — build/pull `deploy/runners/` before isolator path |
