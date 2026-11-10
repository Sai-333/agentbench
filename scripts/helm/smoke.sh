#!/usr/bin/env bash
# Fast, cluster-free Helm smoke: lint + template (default + optional deps + ingress + NetworkPolicies).
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
DEFAULT_OUT="$(helm template agentbench "${CHART}" --namespace agentbench)"
echo "${DEFAULT_OUT}" | grep -q 'kind: NetworkPolicy' \
  || { echo "expected NetworkPolicy resources when networkPolicy.enabled=true" >&2; exit 1; }
echo "${DEFAULT_OUT}" | grep -q 'agentbench-default-deny\|-default-deny' \
  || { echo "expected default-deny NetworkPolicy name" >&2; exit 1; }

echo "==> helm template (networkPolicy disabled)"
DISABLED_OUT="$(helm template agentbench "${CHART}" --namespace agentbench \
  --set networkPolicy.enabled=false)"
if echo "${DISABLED_OUT}" | grep -q 'kind: NetworkPolicy'; then
  echo "expected no NetworkPolicy resources when networkPolicy.enabled=false" >&2
  exit 1
fi

echo "==> helm template (in-cluster deps + ingress)"
DEPS_OUT="$(helm template agentbench "${CHART}" --namespace agentbench \
  --set dependencies.postgres.enabled=true \
  --set dependencies.redis.enabled=true \
  --set dependencies.rabbitmq.enabled=true \
  --set ingress.enabled=true \
  --set ingress.className=nginx \
  --set networkPolicy.egress.allowExternalDataPlane=false)"
echo "${DEPS_OUT}" | grep -q 'kind: NetworkPolicy' \
  || { echo "expected NetworkPolicy with in-cluster deps" >&2; exit 1; }
echo "${DEPS_OUT}" | grep -q 'app.kubernetes.io/component: postgres' \
  || { echo "expected postgres NetworkPolicy when deps enabled" >&2; exit 1; }

echo "==> helm template (existingSecret)"
helm template agentbench "${CHART}" --namespace agentbench \
  --set secrets.create=false \
  --set secrets.existingSecret=my-agentbench-secrets \
  >/dev/null

echo "OK: helm lint + template smoke passed"
