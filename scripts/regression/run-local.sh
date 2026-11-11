#!/usr/bin/env bash
# Run AgentBench *application* Selenium regression against local Compose and
# record a platform-scoped run (not per-consumer / org data).
#
# Prerequisites: docker compose up -d --build from the agentbench repo root.
# Clones Sai-333/agentbench-selenium main at run time (suite SHA not pinned).
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"

API_URL="${AGENTBENCH_API_URL:-http://localhost:8080}"
UI_URL="${AGENTBENCH_BASE_URL:-http://localhost:3000}"
SUITE_DIR="${AGENTBENCH_SELENIUM_DIR:-$ROOT/selenium-suite}"
SUITE_REPO="${AGENTBENCH_SELENIUM_REPO:-https://github.com/Sai-333/agentbench-selenium.git}"

echo "==> AgentBench application regression (platform-scoped, not per-consumer)"
echo "    API: ${API_URL}"
echo "    UI:  ${UI_URL}"

for i in $(seq 1 60); do
  api="$(curl -sf "${API_URL}/actuator/health/readiness" || true)"
  ui="$(curl -sf -o /dev/null -w '%{http_code}' "${UI_URL}/" || true)"
  if printf '%s' "$api" | grep -q UP && [ "$ui" = "200" ]; then
    break
  fi
  if [ "$i" -eq 60 ]; then
    echo "Compose stack not ready (API readiness / UI 200)." >&2
    exit 1
  fi
  sleep 2
done

if [ ! -d "$SUITE_DIR/.git" ]; then
  rm -rf "$SUITE_DIR"
  git clone --depth 1 --branch main "$SUITE_REPO" "$SUITE_DIR"
else
  git -C "$SUITE_DIR" fetch origin main --depth 1
  git -C "$SUITE_DIR" checkout main
  git -C "$SUITE_DIR" reset --hard origin/main
fi
SUITE_SHA="$(git -C "$SUITE_DIR" rev-parse HEAD)"
echo "    Suite main SHA: ${SUITE_SHA} (not pinned)"

python3 -m venv "$SUITE_DIR/.venv"
# shellcheck disable=SC1091
source "$SUITE_DIR/.venv/bin/activate"
python -m pip install -q -r "$SUITE_DIR/requirements.txt"

export AGENTBENCH_API_URL="$API_URL"
export AGENTBENCH_BASE_URL="$UI_URL"
export AGENTBENCH_STARTUP_TIMEOUT="${AGENTBENCH_STARTUP_TIMEOUT:-120}"
export HEADLESS="${HEADLESS:-1}"

python "$SUITE_DIR/tests/seed.py"
mkdir -p "$SUITE_DIR/reports"
set +e
python -m pytest -c "$SUITE_DIR/pytest.ini" --junitxml="$SUITE_DIR/reports/junit.xml" -q
PYTEST_EXIT=$?
set -e

GIT_REPO="${AGENTBENCH_GIT_REPO:-https://github.com/Sai-333/agentbench}"
GIT_BRANCH="$(git -C "$ROOT" rev-parse --abbrev-ref HEAD)"
GIT_SHA="$(git -C "$ROOT" rev-parse HEAD)"

python "$ROOT/scripts/regression/record-run.py" \
  --report "$SUITE_DIR/reports/junit.xml" \
  --git-repo "$GIT_REPO" \
  --git-branch "$GIT_BRANCH" \
  --git-sha "$GIT_SHA" \
  --suite-name selenium \
  --api-url "$API_URL"

echo "Open the top-bar Regression runs page (platform surface, not an org tab)."
exit "$PYTEST_EXIT"
