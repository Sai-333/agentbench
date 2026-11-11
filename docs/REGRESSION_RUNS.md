# Regression runs

Platform-scoped record of a Selenium (or other) suite against a git repo, branch, and commit. These rows are **not** organization data. They do not live under `/api/v1/orgs/{orgId}/...` and they have no `org_id` and no Postgres RLS policy.

Mac Compose (`docker compose up -d --build` from this repo) stays the default local stack. The GitHub workflow runs that same Compose file on a GitHub-hosted Ubuntu runner. It does not turn on AMQPS, the S3 profile, gVisor, or Firecracker, and it does not claim an Oracle host.

## Who can read and ingest

There is no separate platform-admin role. A caller must be authenticated and hold **OWNER or ADMIN on at least one organization**. That is the admin check for this platform data.

| Caller | List, detail, ingest |
|---|---|
| No token | 401 |
| Signed in, no org, or only DEVELOPER / VIEWER | 403 |
| OWNER or ADMIN of any org | allowed |

DEVELOPER and VIEWER do not see run rows. The UI tab is labeled **Regression runs**. People without an admin role see the page title and a short explanation; the API still refuses them.

## What a run looks like

`GET /api/v1/regression-runs` returns the newest runs (default 50, max 100) without case rows. `GET /api/v1/regression-runs/{id}` and a successful ingest return the cases in report order.

```json
{
  "id": "7c1b2a4e-5d6f-4789-a012-3456789abcde",
  "gitRepo": "https://github.com/Sai-333/agentbench",
  "gitBranch": "main",
  "gitSha": "4956497b1dad0cc445f662224ba4f2093a137223",
  "suiteName": "selenium",
  "startedAt": "2026-11-11T04:00:00Z",
  "finishedAt": "2026-11-11T04:00:18Z",
  "passed": 12,
  "failed": 1,
  "skipped": 0,
  "caseCount": 13,
  "scope": "platform",
  "ingestedBy": "0f5a9c2e-1111-4222-8333-444455556666",
  "cases": [
    {
      "name": "tests.test_regression.TestAgentBenchRegression::test_01_shell_loads",
      "status": "passed",
      "durationMs": 840,
      "message": null
    },
    {
      "name": "tests.test_regression.TestAgentBenchRegression::test_09_start_evaluation",
      "status": "failed",
      "durationMs": 1200,
      "message": "AssertionError: Evaluation did not leave QUEUED"
    }
  ]
}
```

`passed`, `failed`, and `skipped` are computed from the stored case rows at ingest. A `summary` object in pytest JSON is not trusted. `caseCount` is the sum of those three counts. The UI lists each run and expands it to the case status, duration, and message.

`scope` is always `platform`.

## Ingest

`POST /api/v1/regression-runs` accepts:

| Body | How to send it |
|---|---|
| JUnit / pytest XML | `Content-Type: application/xml` (or `text/xml`). Git fields as query parameters. |
| pytest-json-report | JSON object with a `tests` array. `outcome` of `passed`, `failed`, `error`, `skipped`, `xpassed`, or `xfailed`. |
| Canonical cases | JSON object with a `cases` array: `name`, `status` (`passed` / `failed` / `skipped`), `durationMs`, optional `message`. |
| Envelope | JSON with `reportFormat` of `junit-xml`, `pytest-json`, or `cases`, plus a `report` string or object. |

Query parameters `gitRepo`, `gitBranch`, `gitSha`, `suiteName`, `startedAt`, and `finishedAt` override the same fields in the JSON body. `gitSha` is 7–64 hex characters. `gitRepo` is required, has no spaces, and is at most 500 characters. `suiteName` is required and at most 120 characters.

Status mapping:

- JUnit `failure` and `error`, and pytest `error`, are stored as **failed**.
- Pytest `xpassed` is **passed**. Pytest `xfailed` is **skipped**.
- Duplicate case names are kept as separate rows.
- An empty `<testsuites>` document is a run with zero cases, not an error.
- A DOCTYPE in XML is rejected (400). Bodies over 2 MB, or more than 5000 cases, are rejected. Cases are not dropped to fit.

If the report has no timestamps, `startedAt` and `finishedAt` default to ingest time. JUnit `testsuite` `timestamp` (no offset is treated as UTC) plus the suite `time` fills them when the request omits both.

The workflow posts pytest's `--junitxml` output. Case names are `classname::name`.

## GitHub Actions

[`.github/workflows/selenium-regression.yml`](../.github/workflows/selenium-regression.yml) runs on every push to every branch.

1. Check out this repo (the commit under test).
2. `docker compose up -d --build --wait`, then require `:8080` readiness `UP` and `:3000` HTTP 200.
3. `git clone --depth 1 --branch main https://github.com/Sai-333/agentbench-selenium.git`. The suite commit is whatever `main` is at clone time. This repo does not pin a suite SHA.
4. Install the suite's `requirements.txt` and Google Chrome.
5. `python tests/seed.py`, which loads `fixtures/mock-data.json`. Pytest also seeds again before cases. That contract is unchanged.
6. `python -m pytest --junitxml=reports/junit.xml` with no node filter, so the full suite on `main` runs, including cases added there later.
7. Register a throwaway user on that runner's Compose stack, create an org (the caller becomes OWNER), and POST the XML to `/api/v1/regression-runs` with this repo's `gitRepo`, `gitBranch`, `gitSha`, and `suiteName=selenium`. The password stays in the runner. It is not a production credential.
8. Upload `selenium-suite/reports/junit.xml` as the `selenium-junit-report` artifact.
9. Write a job summary that lists every case as passed, failed, or skipped, plus totals. The job fails if pytest exits non-zero, if any case failed, if the report is missing, or if the API ingest did not succeed. Skipped cases do not fail the job by themselves.

The summary also prints the suite `main` SHA that was cloned, so a log can be tied to a suite commit without the workflow checking that SHA out on purpose.

## Local

Start Mac Compose as in the README, then open **Regression runs** as an org OWNER or ADMIN. To record a run by hand:

```bash
curl -sS -X POST \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/xml" \
  --data-binary @junit.xml \
  "http://localhost:8080/api/v1/regression-runs?gitRepo=https://github.com/Sai-333/agentbench&gitBranch=main&gitSha=$(git rev-parse HEAD)&suiteName=selenium"
```

`$TOKEN` must belong to an OWNER or ADMIN. Creating an organization as a new user is enough: that user is OWNER of it.
