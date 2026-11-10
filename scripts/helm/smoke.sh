#!/usr/bin/env bash
# Fast, cluster-free Helm smoke: lint + template (default + optional deps + ingress).
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
CHART="${ROOT}/deploy/helm/agentbench"

if ! command -v helm >/dev/null 2>&1; then
  echo "helm not found on PATH" >&2
  exit 1
fi

echo "==> helm lint"
helm lint "${CHART}"

echo "==> helm template (defaults)"
helm template agentbench "${CHART}" --namespace agentbench >/dev/null

echo "==> helm template (in-cluster deps + ingress)"
helm template agentbench "${CHART}" --namespace agentbench \
  --set dependencies.postgres.enabled=true \
  --set dependencies.redis.enabled=true \
  --set dependencies.rabbitmq.enabled=true \
  --set ingress.enabled=true \
  --set ingress.className=nginx \
  >/dev/null

echo "==> helm template (existingSecret)"
helm template agentbench "${CHART}" --namespace agentbench \
  --set secrets.create=false \
  --set secrets.existingSecret=my-agentbench-secrets \
  >/dev/null

echo "OK: helm lint + template smoke passed"
