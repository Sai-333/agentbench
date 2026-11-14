# AgentBench Helm chart

Packages the raw Kubernetes manifests under `deploy/kubernetes/` as a values-driven Helm chart for API (backend), worker, and frontend.

**Primary local path remains Mac Docker Compose.** This chart is the Kubernetes deploy story for staging/demo clusters.

## Honest limits

| Claim | Reality |
|---|---|
| Helm chart for AgentBench apps | **Yes** — install/upgrade/uninstall |
| Operators / CRDs | **Separate** — see `deploy/operator/` (`AgentBenchInstance` + helm-operator) |
| Certified multi-tenant SaaS | **Not claimed** |
| NetworkPolicies | **Yes** — default-deny + allow-needed (`networkPolicy.enabled`, default true) |
| mTLS between services | **Optional** — `mtls.enabled` (default off); cert-manager Certificates **or** openssl Secrets; Spring Boot PEM SSL; not a service mesh |
| RabbitMQ AMQPS (TLS) | **Optional** — `amqps.enabled` (default off); openssl / cert-manager PEMs; Spring SSL bundle; not Istio/Linkerd |
| WORM audit | **Postgres append-only + hash chain** by default (`docs/AUDIT_WORM.md`); optional S3 Object Lock knobs via `config.s3ObjectLock*` (default off; needs Object Lock-enabled bucket) |
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

See `docs/DEPLOYMENT.md` §3c for the allow/deny table and residual risk (L3/L4 only). Pair with §3d mTLS for encryption in transit.

## Optional mTLS (east-west)

**Default off** — Mac Compose and the chart default stay plaintext HTTP. Enable only on Kubernetes:

```bash
# A) openssl Secrets (no cert-manager required)
./scripts/helm/generate-mtls-certs.sh --namespace agentbench --release agentbench --apply
helm upgrade --install agentbench deploy/helm/agentbench -n agentbench \
  --set mtls.enabled=true

# B) cert-manager (cluster must have cert-manager CRDs/controllers)
helm upgrade --install agentbench deploy/helm/agentbench -n agentbench \
  --set mtls.enabled=true \
  --set mtls.certManager.enabled=true
```

What it does: mounts PEM Secrets on API/worker (`SERVER_SSL_*`), switches probes to HTTPS, and (when frontend is enabled) overlays an nginx ConfigMap that proxies `/api` to the backend Service with a client certificate.

**Residual (honest):** not Istio/Linkerd; default `clientAuth=want` so kubelet probes work without a client cert; edge Ingress TLS is separate. Pair with optional AMQPS below for queue TLS. See `docs/DEPLOYMENT.md` §3d.

## Optional RabbitMQ AMQPS (TLS)

**Default off** — Mac Compose and the chart default stay plain AMQP on 5672.

```bash
# A) openssl Secrets
./scripts/rabbitmq/generate-amqps-certs.sh --namespace agentbench --release agentbench --apply
helm upgrade --install agentbench deploy/helm/agentbench -n agentbench \
  --set amqps.enabled=true \
  --set dependencies.rabbitmq.enabled=true

# B) cert-manager
helm upgrade --install agentbench deploy/helm/agentbench -n agentbench \
  --set amqps.enabled=true \
  --set amqps.certManager.enabled=true \
  --set dependencies.rabbitmq.enabled=true
```

What it does: mounts client PEMs on API/worker, sets `SPRING_RABBITMQ_SSL_*` + port 5671, and (when in-cluster RabbitMQ is enabled) configures `listeners.ssl.default` plus an `amqps` Service port.

**Residual (honest):** app-level AMQPS only — not a service mesh; management UI remains HTTP in the lab path; production should use your PKI / managed broker. See `docs/DEPLOYMENT.md` §3f.

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

The default agent is Grok (`config.grokModel`, default `grok-4.7`). `secrets.xaiApiKey` calls `config.xaiBaseUrl` (`https://api.x.ai/v1`). When that key is empty, `secrets.cursorApiKey` (legacy Cursor user API key) calls `config.cursorAgentsUrl` (`POST https://api.cursor.com/v1/agents`). The chart's committed key defaults are empty. See `docs/AGENTS.md`.

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

For CR-driven install/reconcile of this chart, see `deploy/operator/` (`AgentBenchInstance` + helm-operator). Direct `helm upgrade --install` remains fully supported.
