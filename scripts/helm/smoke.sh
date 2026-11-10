#!/usr/bin/env bash
# Fast, cluster-free Helm smoke: lint + template (default + optional deps + ingress + NetworkPolicies + mTLS).
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
if echo "${DEFAULT_OUT}" | grep -q 'SERVER_SSL_ENABLED'; then
  echo "expected no SERVER_SSL_ENABLED when mtls.enabled=false (default)" >&2
  exit 1
fi
if echo "${DEFAULT_OUT}" | grep -q 'kind: Certificate'; then
  echo "expected no cert-manager Certificate when mtls/certManager default off" >&2
  exit 1
fi

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

echo "==> helm template (mtls openssl path — no Certificate CRs)"
MTLS_OUT="$(helm template agentbench "${CHART}" --namespace agentbench \
  --set mtls.enabled=true)"
echo "${MTLS_OUT}" | grep -q 'SERVER_SSL_ENABLED' \
  || { echo "expected SERVER_SSL_ENABLED when mtls.enabled=true" >&2; exit 1; }
echo "${MTLS_OUT}" | grep -q 'scheme: HTTPS' \
  || { echo "expected HTTPS probes when mtls.enabled=true" >&2; exit 1; }
echo "${MTLS_OUT}" | grep -q 'agentbench-frontend-nginx\|-frontend-nginx' \
  || { echo "expected frontend nginx ConfigMap when mtls.enabled=true" >&2; exit 1; }
if echo "${MTLS_OUT}" | grep -q 'kind: Certificate'; then
  echo "expected no Certificate when certManager.enabled=false" >&2
  exit 1
fi

echo "==> helm template (mtls + cert-manager)"
CM_OUT="$(helm template agentbench "${CHART}" --namespace agentbench \
  --set mtls.enabled=true \
  --set mtls.certManager.enabled=true)"
echo "${CM_OUT}" | grep -q 'kind: Certificate' \
  || { echo "expected cert-manager Certificate when mtls.certManager.enabled=true" >&2; exit 1; }
echo "${CM_OUT}" | grep -q 'kind: Issuer' \
  || { echo "expected cert-manager Issuer when mtls.certManager.enabled=true" >&2; exit 1; }
echo "${CM_OUT}" | grep -q 'SERVER_SSL_ENABLED' \
  || { echo "expected Spring SSL env with cert-manager path" >&2; exit 1; }

echo "==> openssl cert script dry-run"
GEN="${ROOT}/scripts/helm/generate-mtls-certs.sh"
chmod +x "${GEN}"
TMP_CERTS="$(mktemp -d)"
"${GEN}" --namespace agentbench --release agentbench --out "${TMP_CERTS}"
test -f "${TMP_CERTS}/agentbench-mtls-backend.yaml" \
  || { echo "expected backend Secret YAML from generate-mtls-certs.sh" >&2; exit 1; }
grep -q 'ca.crt' "${TMP_CERTS}/agentbench-mtls-backend.yaml" \
  || { echo "expected ca.crt in backend Secret" >&2; exit 1; }
rm -rf "${TMP_CERTS}"

echo "==> operator packaging smoke"
"${ROOT}/scripts/operator/smoke.sh"

echo "OK: helm lint + template + operator smoke passed"
