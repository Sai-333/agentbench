# AgentBench Helm chart

Packages the raw Kubernetes manifests under `deploy/kubernetes/` as a values-driven Helm chart for API (backend), worker, and frontend.

**Primary local path remains Mac Docker Compose.** This chart is the Kubernetes deploy story for staging/demo clusters.

## Honest limits

| Claim | Reality |
|---|---|
| Helm chart for AgentBench apps | **Yes** — install/upgrade/uninstall |
| Operators / CRDs | **No** — out of scope |
| Certified multi-tenant SaaS | **Not claimed** |
| NetworkPolicies | **Yes** — default-deny + allow-needed (`networkPolicy.enabled`, default true) |
| mTLS | **Not in this chart** (deferred) |
| WORM audit | **App/DB concern** — Postgres append-only + hash chain (`docs/AUDIT_WORM.md`); chart does not configure S3 Object Lock |
| In-cluster Postgres/Redis/Rabbit | **Optional lab/demo only** — single replica, non-HA |

## NetworkPolicies

On by default. Renders default-deny for release pods, DNS egress, backend/worker/frontend allow-lists, and (when `dependencies.*.enabled`) data-plane ingress only from backend+worker.

```bash
# Explicit enable (default)
helm upgrade --install agentbench deploy/helm/agentbench -n agentbench \
  --set networkPolicy.enabled=true

# Disable
helm upgrade agentbench deploy/helm/agentbench -n agentbench \
  --set networkPolicy.enabled=false
```

See `docs/DEPLOYMENT.md` §3c for the allow/deny table and residual risk (L3/L4 only; not mTLS).

## Dependency strategy

**Default: external.** Point `config.datasourceUrl`, `config.redisHost`, and `config.rabbitmqHost` at managed services (or Compose infra reachable from the cluster). Secrets follow the same placeholder / ExternalSecrets pattern as `deploy/kubernetes/secrets.yaml`.

**Optional in-cluster:** set `dependencies.postgres.enabled`, `dependencies.redis.enabled`, and/or `dependencies.rabbitmq.enabled` for throwaway lab stacks. Flyway V12 still expects the `agentbench_app` role — create it via init SQL or let Flyway manage when using the flyway superuser credentials.

## Install / upgrade / uninstall

```bash
# Replace image tags and secret placeholders first (values file or --set).
helm upgrade --install agentbench deploy/helm/agentbench \
  --namespace agentbench \
  --create-namespace \
  --set backend.image.tag=YOUR_TAG \
  --set worker.image.tag=YOUR_TAG \
  --set frontend.image.tag=YOUR_TAG

helm status agentbench -n agentbench
helm upgrade agentbench deploy/helm/agentbench -n agentbench -f my-values.yaml
helm uninstall agentbench -n agentbench
```

Prefer `secrets.existingSecret` + `secrets.create=false` in real clusters.

Optional Ingress:

```bash
helm upgrade --install agentbench deploy/helm/agentbench -n agentbench \
  --set ingress.enabled=true \
  --set ingress.hosts[0].host=agentbench.example.com
```

Lab deps (not for production):

```bash
helm upgrade --install agentbench deploy/helm/agentbench -n agentbench \
  --set dependencies.postgres.enabled=true \
  --set dependencies.redis.enabled=true \
  --set dependencies.rabbitmq.enabled=true
```

## Lint / template smoke

```bash
./scripts/helm/smoke.sh
# or:
helm lint deploy/helm/agentbench
helm template agentbench deploy/helm/agentbench --namespace agentbench >/dev/null
```

CI runs the same smoke job (no cluster required).

## Relation to raw manifests

`deploy/kubernetes/` remains for `kubectl apply -k` (includes `networkpolicy.yaml`). This chart wraps the same components with Helm values (images, replicas, resources, optional ingress, NetworkPolicies, secret name). Keep both in sync when changing probes, env keys, or policy ports.
