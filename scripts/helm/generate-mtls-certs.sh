#!/usr/bin/env bash
# Generate a private CA + per-service leaf certs for AgentBench Helm mTLS (openssl path).
# Produces Kubernetes TLS Secrets with tls.crt / tls.key / ca.crt — same shape as cert-manager.
# Does NOT require a live cluster unless --apply is passed.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
NAMESPACE="${NAMESPACE:-agentbench}"
RELEASE="${RELEASE:-agentbench}"
OUT_DIR="${OUT_DIR:-${ROOT}/.mtls-certs}"
APPLY=0
DAYS_CA="${DAYS_CA:-3650}"
DAYS_LEAF="${DAYS_LEAF:-825}"

usage() {
  cat <<EOF
Usage: $(basename "$0") [options]

Options:
  --namespace NAME   Kubernetes namespace (default: agentbench)
  --release NAME     Helm release name (default: agentbench)
  --out DIR          Output directory (default: .mtls-certs/)
  --apply            kubectl apply Secrets into --namespace
  --help             Show this help

Secrets created (names match chart defaults):
  <release>-mtls-ca
  <release>-mtls-backend
  <release>-mtls-worker
  <release>-mtls-frontend

Then enable mTLS without cert-manager:
  helm upgrade --install <release> deploy/helm/agentbench -n <ns> \\
    --set mtls.enabled=true
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --namespace) NAMESPACE="$2"; shift 2 ;;
    --release) RELEASE="$2"; shift 2 ;;
    --out) OUT_DIR="$2"; shift 2 ;;
    --apply) APPLY=1; shift ;;
    --help|-h) usage; exit 0 ;;
    *) echo "Unknown option: $1" >&2; usage >&2; exit 1 ;;
  esac
done

if ! command -v openssl >/dev/null 2>&1; then
  echo "openssl not found on PATH" >&2
  exit 1
fi

mkdir -p "${OUT_DIR}"
cd "${OUT_DIR}"

CA_KEY=ca.key
CA_CRT=ca.crt
BACKEND_NAME="${RELEASE}-backend"
WORKER_NAME="${RELEASE}-worker"
FRONTEND_NAME="${RELEASE}-frontend"

echo "==> generating CA"
openssl req -x509 -newkey ec -pkeyopt ec_paramgen_curve:prime256v1 -nodes \
  -keyout "${CA_KEY}" -out "${CA_CRT}" -days "${DAYS_CA}" \
  -subj "/CN=agentbench-mtls-ca" >/dev/null 2>&1

make_leaf() {
  local name="$1"
  local cn="$2"
  shift 2
  local sans=("$@")
  local san_csv=""
  local i=1
  for san in "${sans[@]}"; do
    san_csv+="DNS.${i}:${san},"
    i=$((i + 1))
  done
  san_csv="${san_csv%,}"

  openssl req -newkey ec -pkeyopt ec_paramgen_curve:prime256v1 -nodes \
    -keyout "${name}.key" -out "${name}.csr" \
    -subj "/CN=${cn}" >/dev/null 2>&1

  cat > "${name}.ext" <<EXT
basicConstraints=CA:FALSE
keyUsage=digitalSignature,keyEncipherment
extendedKeyUsage=serverAuth,clientAuth
subjectAltName=${san_csv}
EXT

  openssl x509 -req -in "${name}.csr" -CA "${CA_CRT}" -CAkey "${CA_KEY}" \
    -CAcreateserial -out "${name}.crt" -days "${DAYS_LEAF}" \
    -extfile "${name}.ext" >/dev/null 2>&1

  # Bundle CA for trust (cert-manager puts ca.crt in the leaf Secret)
  cp "${CA_CRT}" "${name}-ca.crt"
  rm -f "${name}.csr" "${name}.ext"
}

echo "==> generating leaf certs"
make_leaf backend "${BACKEND_NAME}" \
  "${BACKEND_NAME}" \
  "${BACKEND_NAME}.${NAMESPACE}" \
  "${BACKEND_NAME}.${NAMESPACE}.svc" \
  "${BACKEND_NAME}.${NAMESPACE}.svc.cluster.local" \
  localhost

make_leaf worker "${WORKER_NAME}" \
  "${WORKER_NAME}" \
  "${WORKER_NAME}.${NAMESPACE}" \
  "${WORKER_NAME}.${NAMESPACE}.svc" \
  "${WORKER_NAME}.${NAMESPACE}.svc.cluster.local" \
  localhost

make_leaf frontend "${FRONTEND_NAME}" \
  "${FRONTEND_NAME}" \
  "${FRONTEND_NAME}.${NAMESPACE}" \
  localhost

write_secret_yaml() {
  local secret_name="$1"
  local crt="$2"
  local key="$3"
  local ca="$4"
  local out="$5"
  cat > "${out}" <<YAML
apiVersion: v1
kind: Secret
metadata:
  name: ${secret_name}
  namespace: ${NAMESPACE}
  labels:
    app.kubernetes.io/name: agentbench
    app.kubernetes.io/instance: ${RELEASE}
    app.kubernetes.io/component: mtls
type: kubernetes.io/tls
data:
  tls.crt: $(base64 -w0 < "${crt}" 2>/dev/null || base64 < "${crt}" | tr -d '\n')
  tls.key: $(base64 -w0 < "${key}" 2>/dev/null || base64 < "${key}" | tr -d '\n')
  ca.crt: $(base64 -w0 < "${ca}" 2>/dev/null || base64 < "${ca}" | tr -d '\n')
YAML
}

echo "==> writing Secret manifests"
write_secret_yaml "${RELEASE}-mtls-ca" ca.crt ca.key ca.crt "${RELEASE}-mtls-ca.yaml"
# CA secret for openssl path still needs tls.key — operators rarely mount it on apps.
write_secret_yaml "${RELEASE}-mtls-backend" backend.crt backend.key ca.crt "${RELEASE}-mtls-backend.yaml"
write_secret_yaml "${RELEASE}-mtls-worker" worker.crt worker.key ca.crt "${RELEASE}-mtls-worker.yaml"
write_secret_yaml "${RELEASE}-mtls-frontend" frontend.crt frontend.key ca.crt "${RELEASE}-mtls-frontend.yaml"

echo "Wrote certs + YAML under ${OUT_DIR}"
echo "  ${RELEASE}-mtls-backend.yaml"
echo "  ${RELEASE}-mtls-worker.yaml"
echo "  ${RELEASE}-mtls-frontend.yaml"

if [[ "${APPLY}" -eq 1 ]]; then
  if ! command -v kubectl >/dev/null 2>&1; then
    echo "kubectl not found; cannot --apply" >&2
    exit 1
  fi
  echo "==> kubectl apply"
  kubectl apply -f "${RELEASE}-mtls-backend.yaml"
  kubectl apply -f "${RELEASE}-mtls-worker.yaml"
  kubectl apply -f "${RELEASE}-mtls-frontend.yaml"
fi

echo "OK: enable with --set mtls.enabled=true (certManager.enabled=false)"
