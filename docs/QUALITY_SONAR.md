# AgentBench — Optional SonarQube / SonarCloud quality integration

Checkstyle, SpotBugs, and PMD remain the **primary** local quality tools. Sonar is an **optional** scorer / report importer for the evaluation quality stage. It is **not** the sole scoring authority.

## Default (local Compose / CI)

Sonar is **off**. No live Sonar server is required for tests or demos.

```yaml
agentbench.quality.sonar.enabled: false   # AGENTBENCH_SONAR_ENABLED=false
```

When disabled, quality metrics still include a `sonar` node with `stub=true` and `notes=sonar_disabled`. Primary tools continue to score as before.

## How it works

For each evaluation quality stage, AgentBench collects baseline and post-patch reports:

1. **Workspace report import** (preferred for baseline↔post separation): if the workspace contains a Sonar issues JSON file at one of:
   - `target/sonar-issues.json`
   - `sonar-issues.json`
   - `.agentbench/sonar-issues.json`  
   that file is parsed (same shape as Sonar Web API `/api/issues/search`).
2. **Web API fetch** (when enabled and no local report): `GET {baseUrl}/api/issues/search` with `componentKeys`, optional `organization` / `branch` / `pullRequest`. Auth is HTTP Basic with the user token as username (Sonar convention). Tokens are never logged.
3. Findings are fingerprinted and diffed baseline↔post into **new** vs **pre-existing**, same as the primary tools.
4. When Sonar returns real (non-stub) results, those findings contribute to the aggregate `code_quality` normalized score **alongside** Checkstyle/SpotBugs/PMD. The tool label becomes `checkstyle+spotbugs+pmd+sonar`.

## Enable for Compose / deploy

```bash
# .env (never commit real tokens)
AGENTBENCH_SONAR_ENABLED=true
AGENTBENCH_SONAR_BASE_URL=https://sonarcloud.io   # or http://sonarqube:9000
AGENTBENCH_SONAR_TOKEN=squ_...                    # user token
AGENTBENCH_SONAR_PROJECT_KEY=my-project-key
AGENTBENCH_SONAR_ORGANIZATION=my-org              # SonarCloud only; optional self-hosted
# Optional filters:
# AGENTBENCH_SONAR_BRANCH=main
# AGENTBENCH_SONAR_PULL_REQUEST=123
# AGENTBENCH_SONAR_TIMEOUT_SEC=20
# AGENTBENCH_SONAR_MAX_ISSUES=500
```

Wire the same variables into `backend` and `worker` (Compose already forwards them when set). Restart after changing env.

### Offline / eval-friendly import

For honest new-vs-pre-existing scoring without a live server, export issues JSON into each workspace (baseline and patched) before the quality stage, e.g. from a prior scanner run or fixture. CI unit tests use WireMock + fixtures under `backend/src/test/resources/fixtures/`.

Optional per-workspace branch override for API mode: write the branch name to `.agentbench/sonar-branch`.

## Honesty

| Claim | Status |
|---|---|
| Optional Sonar integration (import + API) | Done when enabled |
| Checkstyle / SpotBugs / PMD primary | Unchanged |
| Sonar as sole scoring authority | **Not claimed** |
