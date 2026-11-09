#!/usr/bin/env bash
# Secret-free smoke checks against a local AgentBench Compose stack.
# Uses published host ports from .env defaults (BACKEND_PORT=8080, FRONTEND_PORT=3000).
# Exit 0 only if all required checks pass.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "${ROOT}"

BACKEND_PORT="${BACKEND_PORT:-8080}"
FRONTEND_PORT="${FRONTEND_PORT:-3000}"
COMPOSE=(docker compose)
if [[ -f docker-compose.prod.yml ]] && docker compose -f docker-compose.yml -f docker-compose.prod.yml ps >/dev/null 2>&1; then
  # Prefer prod overlay project if that is what is running; fall back to base.
  if docker compose -f docker-compose.yml -f docker-compose.prod.yml ps --status running -q 2>/dev/null | grep -q .; then
    COMPOSE=(docker compose -f docker-compose.yml -f docker-compose.prod.yml)
  fi
fi

fail=0
pass() { echo "OK  $*"; }
bad()  { echo "FAIL $*"; fail=1; }

echo "==> Compose services"
"${COMPOSE[@]}" ps || bad "compose ps"

echo "==> Postgres"
if "${COMPOSE[@]}" exec -T postgres pg_isready -U "${POSTGRES_USER:-agentbench}" -d "${POSTGRES_DB:-agentbench}" >/dev/null 2>&1; then
  pass "postgres pg_isready"
else
  bad "postgres pg_isready"
fi

echo "==> Redis"
if "${COMPOSE[@]}" exec -T redis redis-cli ping 2>/dev/null | grep -q PONG; then
  pass "redis PING"
else
  bad "redis PING"
fi

echo "==> RabbitMQ"
if "${COMPOSE[@]}" exec -T rabbitmq rabbitmq-diagnostics -q ping >/dev/null 2>&1; then
  pass "rabbitmq ping"
else
  bad "rabbitmq ping"
fi

check_http() {
  local name="$1" url="$2"
  if curl -sf --max-time 10 "${url}" >/dev/null; then
    pass "${name} ${url}"
  else
    bad "${name} ${url}"
  fi
}

echo "==> Backend / frontend HTTP"
check_http "liveness"  "http://127.0.0.1:${BACKEND_PORT}/actuator/health/liveness"
check_http "readiness" "http://127.0.0.1:${BACKEND_PORT}/actuator/health/readiness"
check_http "api info"  "http://127.0.0.1:${BACKEND_PORT}/api/v1/info"
check_http "openapi"   "http://127.0.0.1:${BACKEND_PORT}/v3/api-docs"
# swagger-ui.html may redirect; accept 2xx/3xx
if code="$(curl -s -o /dev/null -w '%{http_code}' --max-time 10 "http://127.0.0.1:${BACKEND_PORT}/swagger-ui.html")"; then
  if [[ "${code}" =~ ^[23] ]]; then
    pass "swagger HTTP ${code}"
  else
    bad "swagger HTTP ${code}"
  fi
else
  bad "swagger unreachable"
fi
check_http "frontend"  "http://127.0.0.1:${FRONTEND_PORT}/"

if [[ "${fail}" -ne 0 ]]; then
  echo "Healthcheck failed." >&2
  exit 1
fi
echo "All checks passed."
