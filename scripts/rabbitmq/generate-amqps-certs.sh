#!/usr/bin/env bash
# Generate a private CA + RabbitMQ server/client PEMs for opt-in AMQPS (TLS).
# Also writes a lab rabbitmq.conf and optional Kubernetes Secret YAML.
# Does NOT require a live broker or cluster unless --apply is passed.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
NAMESPACE="${NAMESPACE:-agentbench}"
RELEASE="${RELEASE:-agentbench}"
OUT_DIR="${OUT_DIR:-${ROOT}/.amqps-certs}"
APPLY=0
DAYS_CA="${DAYS_CA:-3650}"
DAYS_LEAF="${DAYS_LEAF:-825}"
# Lab Compose: keep plaintext 5672 alongside 5671 unless REQUIRE_TLS=1
REQUIRE_TLS="${REQUIRE_TLS:-0}"
# Broker peer verify: verify_peer (default) or verify_none
BROKER_VERIFY="${BROKER_VERIFY:-verify_peer}"
FAIL_IF_NO_PEER_CERT="${FAIL_IF_NO_PEER_CERT:-false}"

usage() {
  cat <<EOF
Usage: $(basename "$0") [options]

Options:
  --namespace NAME   Kubernetes namespace (default: agentbench)
  --release NAME     Helm release name (default: agentbench)
  --out DIR          Output directory (default: .amqps-certs/)
  --require-tls      Broker rabbitmq.conf disables plaintext listeners.tcp
  --apply            kubectl apply Secrets into --namespace
  --help             Show this help

Produces under OUT_DIR:
  ca.crt / ca.key
  server.crt / server.key   (CN/SAN: rabbitmq, <release>-rabbitmq, localhost)
  client.crt / client.key   (for Spring API/worker mutual TLS)
  rabbitmq.conf             (listeners.ssl.default = 5671)
  <release>-amqps-{ca,server,client}.yaml  (TLS Secrets)

Compose overlay:
  ./scripts/rabbitmq/generate-amqps-certs.sh
  docker compose -f docker-compose.yml -f docker-compose.amqps.yml up -d

Helm (openssl path, no cert-manager):
  ./scripts/rabbitmq/generate-amqps-certs.sh --namespace agentbench --release agentbench --apply
  helm upgrade --install agentbench deploy/helm/agentbench -n agentbench \\
    --set amqps.enabled=true
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --namespace) NAMESPACE="$2"; shift 2 ;;
    --release) RELEASE="$2"; shift 2 ;;
    --out) OUT_DIR="$2"; shift 2 ;;
    --require-tls) REQUIRE_TLS=1; shift ;;
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
RABBIT_SVC="${RELEASE}-rabbitmq"

echo "==> generating CA"
openssl req -x509 -newkey ec -pkeyopt ec_paramgen_curve:prime256v1 -nodes \
  -keyout "${CA_KEY}" -out "${CA_CRT}" -days "${DAYS_CA}" \
  -subj "/CN=agentbench-amqps-ca" >/dev/null 2>&1

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

  rm -f "${name}.csr" "${name}.ext"
}

echo "==> generating server + client leaf certs"
make_leaf server "${RABBIT_SVC}" \
  rabbitmq \
  "${RABBIT_SVC}" \
  "${RABBIT_SVC}.${NAMESPACE}" \
  "${RABBIT_SVC}.${NAMESPACE}.svc" \
  "${RABBIT_SVC}.${NAMESPACE}.svc.cluster.local" \
  localhost

make_leaf client "${RELEASE}-amqps-client" \
  "${RELEASE}-amqps-client" \
  "${RELEASE}-backend" \
  "${RELEASE}-worker" \
  localhost

# Lab Compose mounts these into the container; world-readable avoids UID mismatch.
chmod 644 ca.crt server.crt client.crt
chmod 644 ca.key server.key client.key

echo "==> writing rabbitmq.conf"
{
  if [[ "${REQUIRE_TLS}" -eq 1 ]]; then
    echo "listeners.tcp = none"
  else
    echo "listeners.tcp.default = 5672"
  fi
  cat <<CONF
listeners.ssl.default = 5671
ssl_options.cacertfile = /etc/rabbitmq/certs/ca.crt
ssl_options.certfile   = /etc/rabbitmq/certs/server.crt
ssl_options.keyfile    = /etc/rabbitmq/certs/server.key
ssl_options.verify     = ${BROKER_VERIFY}
ssl_options.fail_if_no_peer_cert = ${FAIL_IF_NO_PEER_CERT}
management.tcp.port = 15672
CONF
} > rabbitmq.conf

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
    app.kubernetes.io/component: amqps
type: kubernetes.io/tls
data:
  tls.crt: $(base64 -w0 < "${crt}" 2>/dev/null || base64 < "${crt}" | tr -d '\n')
  tls.key: $(base64 -w0 < "${key}" 2>/dev/null || base64 < "${key}" | tr -d '\n')
  ca.crt: $(base64 -w0 < "${ca}" 2>/dev/null || base64 < "${ca}" | tr -d '\n')
YAML
}

echo "==> writing Secret manifests"
write_secret_yaml "${RELEASE}-amqps-ca" ca.crt ca.key ca.crt "${RELEASE}-amqps-ca.yaml"
write_secret_yaml "${RELEASE}-amqps-server" server.crt server.key ca.crt "${RELEASE}-amqps-server.yaml"
write_secret_yaml "${RELEASE}-amqps-client" client.crt client.key ca.crt "${RELEASE}-amqps-client.yaml"

echo "Wrote certs + YAML under ${OUT_DIR}"
echo "  rabbitmq.conf"
echo "  ${RELEASE}-amqps-server.yaml"
echo "  ${RELEASE}-amqps-client.yaml"

if [[ "${APPLY}" -eq 1 ]]; then
  if ! command -v kubectl >/dev/null 2>&1; then
    echo "kubectl not found; cannot --apply" >&2
    exit 1
  fi
  echo "==> kubectl apply"
  kubectl apply -f "${RELEASE}-amqps-server.yaml"
  kubectl apply -f "${RELEASE}-amqps-client.yaml"
fi

echo "OK: enable Compose with docker-compose.amqps.yml or Helm --set amqps.enabled=true"
