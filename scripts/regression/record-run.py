#!/usr/bin/env python3
"""Record an AgentBench *application* regression run (platform-scoped).

Posts a JUnit / pytest XML report to POST /api/v1/regression-runs.
This is not consumer / organization data: the API has no org_id. A throwaway
OWNER org is created only so the caller satisfies the OWNER/ADMIN gate.

Examples:

  python scripts/regression/record-run.py \\
    --report selenium-suite/reports/junit.xml \\
    --git-repo https://github.com/Sai-333/agentbench \\
    --git-branch main \\
    --git-sha "$(git rev-parse HEAD)" \\
    --suite-name selenium

  AGENTBENCH_API_URL=http://localhost:8080 python scripts/regression/record-run.py \\
    --report reports/junit.xml --git-repo "$REPO" --git-branch "$BRANCH" --git-sha "$SHA"
"""

from __future__ import annotations

import argparse
import json
import os
import secrets
import sys
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path


def _call(
    method: str,
    url: str,
    *,
    body: dict | None = None,
    token: str | None = None,
    content_type: str = "application/json",
    raw: bytes | None = None,
):
    data = raw if raw is not None else (None if body is None else json.dumps(body).encode())
    headers = {"Accept": "application/json"}
    if data is not None and content_type:
        headers["Content-Type"] = content_type
    if token:
        headers["Authorization"] = f"Bearer {token}"
    request = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(request, timeout=60) as response:
            payload = response.read()
            return json.loads(payload.decode()) if payload else None
    except urllib.error.HTTPError as exc:
        detail = exc.read().decode("utf-8", errors="replace")[:800]
        raise SystemExit(
            f"{method} {urllib.parse.urlparse(url).path} failed: HTTP {exc.code} {detail}"
        ) from None


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Ingest a platform-scoped AgentBench application regression run."
    )
    parser.add_argument("--report", required=True, type=Path, help="JUnit / pytest XML path")
    parser.add_argument("--git-repo", required=True, help="Application git repository URL")
    parser.add_argument("--git-branch", required=True, help="Application git branch")
    parser.add_argument("--git-sha", required=True, help="Application git commit SHA (7–64 hex)")
    parser.add_argument(
        "--suite-name",
        default="selenium",
        help="Suite label stored on the platform run (default: selenium)",
    )
    parser.add_argument(
        "--api-url",
        default=os.environ.get("AGENTBENCH_API_URL", "http://localhost:8080"),
        help="AgentBench API base (default: AGENTBENCH_API_URL or http://localhost:8080)",
    )
    parser.add_argument(
        "--email",
        default="",
        help="Optional existing OWNER/ADMIN email (otherwise registers a throwaway user)",
    )
    parser.add_argument(
        "--password",
        default="",
        help="Password for --email (or generated for the throwaway user)",
    )
    parser.add_argument(
        "--org-slug",
        default="",
        help="Optional org slug used only to satisfy OWNER/ADMIN; not attached to the run",
    )
    args = parser.parse_args()

    report: Path = args.report
    if not report.is_file():
        raise SystemExit(f"JUnit report missing: {report}")

    base = str(args.api_url).rstrip("/")
    run_id = os.environ.get("GITHUB_RUN_ID", "local")
    attempt = os.environ.get("GITHUB_RUN_ATTEMPT", "1")
    email = args.email or f"ci-regression-{run_id}-{attempt}@example.com"
    password = args.password or secrets.token_urlsafe(18)
    slug = args.org_slug or f"ci-reg-{run_id}-{attempt}"[:63]

    if args.email:
        tokens = _call(
            "POST",
            base + "/api/v1/auth/login",
            body={"email": email, "password": password, "provider": "local"},
        )
    else:
        tokens = _call(
            "POST",
            base + "/api/v1/auth/register",
            body={
                "email": email,
                "password": password,
                "displayName": "CI Regression",
            },
        )
    token = tokens["accessToken"]

    # OWNER/ADMIN of any org is required for platform regression ingest.
    # The org is never written onto platform.regression_runs.
    _call(
        "POST",
        base + "/api/v1/orgs",
        body={
            "name": f"CI Regression {run_id}",
            "slug": slug,
        },
        token=token,
    )

    query = urllib.parse.urlencode(
        {
            "gitRepo": args.git_repo,
            "gitBranch": args.git_branch,
            "gitSha": args.git_sha,
            "suiteName": args.suite_name,
        }
    )
    created = _call(
        "POST",
        base + "/api/v1/regression-runs?" + query,
        token=token,
        content_type="application/xml",
        raw=report.read_bytes(),
    )
    cases = created.get("cases") or []
    passed = int(created["passed"])
    failed = int(created["failed"])
    skipped = int(created["skipped"])
    case_count = int(created["caseCount"])
    if passed + failed + skipped != case_count or len(cases) != case_count:
        raise SystemExit(
            f"Recorded run {created.get('id')} did not return every case "
            f"(passed={passed} failed={failed} skipped={skipped} "
            f"caseCount={case_count} rows={len(cases)})"
        )
    scope = created.get("scope", "platform")
    print(
        f"Recorded AgentBench application regression run {created['id']} "
        f"scope={scope} passed={passed} failed={failed} skipped={skipped} cases={case_count}"
    )
    print(json.dumps({"id": created["id"], "scope": scope, "passed": passed, "failed": failed, "skipped": skipped}, indent=2))
    return 0


if __name__ == "__main__":
    sys.exit(main())
