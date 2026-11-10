#!/usr/bin/env bash
# Cluster-free operator packaging smoke: CRD YAML, watches, helm lint/template.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
OP="${ROOT}/deploy/operator"
CHART="${OP}/chart"
CRD="${CHART}/crds/agentbench.io_agentbenchinstances.yaml"
WATCHES="${OP}/watches.yaml"
SAMPLE="${OP}/samples/agentbenchinstance.yaml"
DOCKERFILE="${OP}/Dockerfile"

if ! command -v helm >/dev/null 2>&1; then
  echo "helm not found on PATH" >&2
  exit 1
fi

if ! command -v python3 >/dev/null 2>&1; then
  echo "python3 not found on PATH" >&2
  exit 1
fi

echo "==> validate CRD YAML"
python3 - "${CRD}" <<'PY'
import sys, yaml
path = sys.argv[1]
with open(path, encoding="utf-8") as f:
    docs = list(yaml.safe_load_all(f))
assert len(docs) == 1, f"expected 1 document, got {len(docs)}"
doc = docs[0]
assert doc.get("kind") == "CustomResourceDefinition", doc.get("kind")
assert doc["metadata"]["name"] == "agentbenchinstances.agentbench.io"
spec = doc["spec"]
assert spec["group"] == "agentbench.io"
assert spec["names"]["kind"] == "AgentBenchInstance"
versions = {v["name"]: v for v in spec["versions"]}
assert "v1alpha1" in versions
assert versions["v1alpha1"].get("storage") is True
schema = versions["v1alpha1"]["schema"]["openAPIV3Schema"]["properties"]
assert "spec" in schema and "status" in schema
print("CRD OK:", path)
PY

echo "==> validate watches.yaml"
python3 - "${WATCHES}" <<'PY'
import sys, yaml
path = sys.argv[1]
with open(path, encoding="utf-8") as f:
    watches = yaml.safe_load(f)
assert isinstance(watches, list) and len(watches) >= 1
w = watches[0]
assert w["group"] == "agentbench.io"
assert w["version"] == "v1alpha1"
assert w["kind"] == "AgentBenchInstance"
assert w["chart"] == "helm-charts/agentbench"
print("watches OK:", path)
PY

echo "==> validate sample CR"
python3 - "${SAMPLE}" <<'PY'
import sys, yaml
path = sys.argv[1]
with open(path, encoding="utf-8") as f:
    doc = yaml.safe_load(f)
assert doc["apiVersion"] == "agentbench.io/v1alpha1"
assert doc["kind"] == "AgentBenchInstance"
assert "spec" in doc and isinstance(doc["spec"], dict)
print("sample OK:", path)
PY

echo "==> Dockerfile references helm-operator + chart copy"
grep -q 'operator-framework/helm-operator' "${DOCKERFILE}" \
  || { echo "expected helm-operator base image in Dockerfile" >&2; exit 1; }
grep -q 'deploy/helm/agentbench' "${DOCKERFILE}" \
  || { echo "expected COPY of deploy/helm/agentbench in Dockerfile" >&2; exit 1; }
grep -q 'watches.yaml' "${DOCKERFILE}" \
  || { echo "expected COPY of watches.yaml in Dockerfile" >&2; exit 1; }

echo "==> helm lint (operator chart)"
helm lint "${CHART}"

echo "==> helm template (operator defaults)"
OUT="$(helm template agentbench-operator "${CHART}" --namespace agentbench-system)"
echo "${OUT}" | grep -q 'kind: Deployment' \
  || { echo "expected operator Deployment" >&2; exit 1; }
echo "${OUT}" | grep -q 'kind: ClusterRole' \
  || { echo "expected ClusterRole" >&2; exit 1; }
echo "${OUT}" | grep -q 'kind: ClusterRoleBinding' \
  || { echo "expected ClusterRoleBinding" >&2; exit 1; }
echo "${OUT}" | grep -q 'kind: ServiceAccount' \
  || { echo "expected ServiceAccount" >&2; exit 1; }
echo "${OUT}" | grep -q 'agentbenchinstances' \
  || { echo "expected RBAC rules for agentbenchinstances" >&2; exit 1; }
if echo "${OUT}" | grep -q 'kind: CustomResourceDefinition'; then
  echo "CRDs live under chart/crds/ (Helm installs them separately; not in template output) — OK"
fi

echo "==> helm show crds"
CRDS_OUT="$(helm show crds "${CHART}")"
echo "${CRDS_OUT}" | grep -q 'kind: CustomResourceDefinition' \
  || { echo "expected CRD from helm show crds" >&2; exit 1; }
echo "${CRDS_OUT}" | grep -q 'AgentBenchInstance' \
  || { echo "expected AgentBenchInstance in CRD" >&2; exit 1; }

# Optional kubeconform when present (not required in CI image).
if command -v kubeconform >/dev/null 2>&1; then
  echo "==> kubeconform (templated operator manifests)"
  echo "${OUT}" | kubeconform -strict -ignore-missing-schemas -
  echo "==> kubeconform (CRD)"
  kubeconform -strict -kubernetes-version 1.29.0 "${CRD}"
else
  echo "==> kubeconform skipped (not on PATH)"
fi

echo "OK: operator CRD + helm lint/template smoke passed"
