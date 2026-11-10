# Scoring methodology

Honest reference for visible transitions, hidden post-only rates, flake quarantine, and baseline confirmation (R5).

## Visible transitions (comparable identities)

On the **visible** suite, AgentBench classifies each case that appears in both baseline and post using the same case key:

| Label | Baseline → Post | Meaning |
|---|---|---|
| **F2P** | FAIL → PASS | Target-style fix signal |
| **P2P** | PASS → PASS | Stable control |
| **P2F** | PASS → FAIL | Regression |
| **F2F** | FAIL → FAIL | Unfixed failure |

Weighted score uses these transitions for target resolution and regression metrics. Flaky F2P (inconsistent across repeat runs) is **not** trusted as a clean fix — the transition label is dropped and excluded from target resolution.

## Hidden suites (never F2P)

Hidden suites run only after the patch is sealed, in a separate stage. Scoring uses a **post-only pass rate**. Hidden outcomes are **never** labeled F2P/P2P/P2F/F2F.

## Flake quarantine (R5)

Known-flaky tests can be quarantined so they do not unfairly fail or inflate agent scores:

| Source | Scope |
|---|---|
| `POST /api/v1/orgs/{orgId}/flake-quarantines` (OWNER/ADMIN) | Org DB rows: suite-level (`caseKey` null) or case-level |
| Task `evalConfig.quarantinedCaseKeys` | Explicit case keys for that evaluation |
| Suite JSON `quarantined: true` | All cases in that suite |

Quarantined cases are still **recorded** (status + `quarantined` meta) for transparency, but:

- receive **no** transition label
- are **excluded** from scoring denominators (target / regression / hidden / edge)
- do not count toward `requireTargetFailAtBaseline`
- do not block baseline confirmation when the confirmation gate is on

List with `GET .../flake-quarantines` (VIEWER+). Deactivate with `DELETE .../flake-quarantines/{id}`.

## Baseline confirmation gate (R5)

When enabled, the baseline stage must be **stable** across repeated suite runs before the evaluation advances to `INVOKING_AGENT`.

| Knob | Default | Effect |
|---|---|---|
| `AGENTBENCH_EVAL_BASELINE_CONFIRMATION_ENABLED` | `false` | Platform-wide gate |
| `AGENTBENCH_EVAL_BASELINE_CONFIRMATION_RUNS` | `2` | Minimum confirmation runs when gate is on (clamped 2–10) |
| Task `evalConfig.requireBaselineConfirmation` | — | Per-eval enable |
| Task `evalConfig.baselineConfirmRuns` | — | Per-eval run count |

Unstable **non-quarantined** cases fail the evaluation with `BASELINE_UNCONFIRMED` (no agent retry). Successful confirmation sets `test_runs.baseline_confirmed = true`.

Related: `AGENTBENCH_EVAL_FLAKY_REPEAT_RUNS` / scoring-profile `flaky_repeat_runs` still drive general flaky detection on visible/hidden post stages.

## Invalid baseline

If `requireTargetFailAtBaseline` (default true) and no non-quarantined target FAIL is observed, evaluation ends `FAILED` / `BASELINE_NOT_REPRODUCIBLE`.

## Residuals / not claimed

- Quarantine is **operator-declared** (or task/suite config) — not an automatic ML flake classifier across historical evaluations.
- Confirmation gate is **opt-in** (platform or per-task); default demos keep it off for speed.
- Does not replace pinned runner images or Docker/gVisor/Firecracker isolation.
