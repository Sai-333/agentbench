# AgentBench operator (Helm + CRD)

Minimal packaging so an `AgentBenchInstance` custom resource installs and reconciles the existing app chart at `deploy/helm/agentbench/`.

Uses the Operator Framework **helm-operator** runtime (`watches.yaml` → Helm chart). This is intentionally lean — not an unfinished Kubebuilder Go tree, and **not** certified multi-cloud SaaS.

## Layout

| Path | Role |
|---|---|
| `chart/` | Helm chart that installs CRD + RBAC + operator Deployment |
| `chart/crds/` | `AgentBenchInstance` CRD (`agentbench.io/v1alpha1`) |
| `watches.yaml` | Maps CR → `helm-charts/agentbench` inside the operator image |
| `Dockerfile` | `FROM quay.io/operator-framework/helm-operator` + copy watches + app chart |
| `samples/` | Example `AgentBenchInstance` |
| `PROJECT` | Operator-SDK-style skeleton marker |

The app chart under `deploy/helm/agentbench/` is unchanged and remains the preferred direct Helm path.

## Prerequisites

- Kubernetes cluster with RBAC
- Docker (to build the operator image)
- Helm 3.x
- `kubectl`

Mac Docker Compose remains the primary local day-to-day path; use this operator on a real/demo cluster.

## Install

```bash
# From repository root — build the operator image (embeds app chart + watches)
docker build -f deploy/operator/Dockerfile -t agentbench-operator:dev .

# Install CRD + controller
helm upgrade --install agentbench-operator deploy/operator/chart \
  --namespace agentbench-system \
  --create-namespace \
  --set image.repository=agentbench-operator \
  --set image.tag=dev \
  --set image.pullPolicy=IfNotPresent

# Target namespace + instance (edit image tags in the sample first)
kubectl create namespace agentbench --dry-run=client -o yaml | kubectl apply -f -
kubectl apply -f deploy/operator/samples/agentbenchinstance.yaml

kubectl get agentbenchinstances -n agentbench
kubectl get all -n agentbench
```

Uninstall instance then operator:

```bash
kubectl delete -f deploy/operator/samples/agentbenchinstance.yaml
helm uninstall agentbench-operator -n agentbench-system
# CRDs installed via Helm crds/ are not removed on uninstall by design
kubectl delete crd agentbenchinstances.agentbench.io
```

## Spec → Helm values

`AgentBenchInstance.spec` is passed through as Helm values for `deploy/helm/agentbench`. See that chart’s `values.yaml` / README for images, secrets, NetworkPolicies, optional mTLS, and lab dependencies.

## Validate without a cluster

```bash
./scripts/operator/smoke.sh
# also covered by CI helm job via ./scripts/helm/smoke.sh (calls operator smoke)
```

## Honest limits

| Claim | Reality |
|---|---|
| CRD + controller installs app chart from a CR | **Yes** (helm-operator) |
| Direct Helm chart still supported | **Yes** — `deploy/helm/agentbench/` |
| Certified multi-cloud / multi-tenant SaaS | **Not claimed** |
| Full Kubebuilder Go reconcile loop | **No** — helm-operator skeleton only |
| Live cluster required for CI | **No** — lint/template/YAML checks only |
