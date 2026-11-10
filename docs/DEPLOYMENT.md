# AgentBench — Deployment

**Status:** Milestone 9 (production-minded Compose + Kubernetes templates)  
**Audience:** Operators bringing up a demo / staging stack. Not a certified multi-tenant SaaS runbook.

## 1. Compose (recommended local / single-host)

### Dev / demo

```bash
cp .env.example .env
docker compose up -d --build
```

Default artifact backend is **local filesystem** (`ARTIFACT_STORAGE=local`) on the `artifacts_data` volume.

Verify:

```bash
curl -sf http://localhost:8080/actuator/health/liveness
curl -sf http://localhost:8080/actuator/health/readiness
curl -sf http://localhost:8080/api/v1/info
curl -sf http://localhost:3000/ | head
```

### Optional OIDC (Google / GitHub)

Disabled by default. Password registration/login always works. To enable “Continue with Google/GitHub”:

1. Register OAuth apps and set redirect URIs — see **[docs/AUTH_OIDC.md](AUTH_OIDC.md)**.
2. Put client id/secret + `*_ENABLED=true` in `.env` (Compose passes them to `backend`).
3. `docker compose up -d --build` and open http://localhost:3000 — buttons appear only when configured.

Never commit real client secrets. Endpoint overrides exist for CI mocks (`AGENTBENCH_OIDC_*_TOKEN_URI`, etc.).

### Optional SAML 2.0 (enterprise SSO)

Disabled by default. Config-driven single IdP (metadata URL or XML) — see **[docs/AUTH_SAML.md](AUTH_SAML.md)**. Set `AGENTBENCH_SAML_ENABLED=true` plus entity ID, ACS URL, and IdP metadata; multi-IdP admin UI is not included.

### S3-compatible artifacts (MinIO profile)

Local filesystem remains the simple-demo default. To store blobs in MinIO:

```bash
cp .env.example .env
# ARTIFACT_STORAGE=s3
# AGENTBENCH_S3_* already default to the Compose MinIO service
ARTIFACT_STORAGE=s3 docker compose --profile s3 up -d --build
docker compose --profile s3 ps minio   # should be healthy
```

| Variable | Purpose |
|---|---|
| `ARTIFACT_STORAGE` | `local` (default) or `s3` |
| `AGENTBENCH_S3_ENDPOINT` | e.g. `http://minio:9000` in Compose |
| `AGENTBENCH_S3_BUCKET` | default `agentbench-artifacts` |
| `AGENTBENCH_S3_ACCESS_KEY` / `SECRET_KEY` | MinIO root user/password |
| `AGENTBENCH_S3_PATH_STYLE` | `true` for MinIO |
| `AGENTBENCH_S3_CREATE_BUCKET` | create bucket on API/worker startup |
| `AGENTBENCH_ARTIFACT_RETENTION_DAYS` | TTL sweep (blobs + local workspaces) |
| `AGENTBENCH_ARTIFACT_MAX_OBJECT_BYTES` | hard ceiling rejected at `store()` |

Retention cleanup: scheduled sweep deletes expired objects via the active `ArtifactStorage` (filesystem walk or S3 list/delete) and always TTL-sweeps local `workspaces/` when using S3. Audit rows land in `platform.artifact_cleanup_runs`. Console: http://localhost:9001.

### Production-ish overlay

Requires strong secrets in `.env` (compose will refuse empty required vars):

```bash
cp .env.example .env
# edit POSTGRES_PASSWORD, RABBITMQ_PASSWORD, AGENTBENCH_JWT_SECRET, AGENTBENCH_SECRETS_ENCRYPTION_KEY
docker compose -f docker-compose.yml -f docker-compose.prod.yml up -d --build
```

Notes:

- Graceful shutdown: Spring `server.shutdown=graceful` + Compose `stop_grace_period`.
- Worker may optionally mount host `docker.sock` **only on the worker service** for `RuntimeIsolator`. Never mount sock into evaluation containers.
- Reverse-proxy TLS termination and authn at the edge are expected in real deployments; Compose still binds plaintext localhost ports for demos.

## 2. Health / readiness

| Probe | Path | Use |
|---|---|---|
| Liveness | `/actuator/health/liveness` | Restart when process is wedged |
| Readiness | `/actuator/health/readiness` | Remove from load balancer when DB/Redis/Rabbit down |
| Aggregate | `/actuator/health` | Ops debugging (details gated `when_authorized`) |

Compose and Kubernetes templates probe readiness/liveness separately.

## 3. Kubernetes (raw templates)

Manifests live under `deploy/kubernetes/`:

| File | Purpose |
|---|---|
| `namespace.yaml` | `agentbench` namespace |
| `configmap.yaml` | Non-secret runtime config |
| `secrets.yaml` | **Template placeholders only** — replace via SealedSecrets/ExternalSecrets |
| `backend-deployment.yaml` | API Deployment + Service + probes |
| `worker-deployment.yaml` | Worker profile Deployment + Service |
| `frontend-deployment.yaml` | Static UI Deployment + Service |
| `networkpolicy.yaml` | Default-deny + allow-needed NetworkPolicies (§3c) |
| `kustomization.yaml` | Simple kustomize bundle |

```bash
# After replacing image tags and secret values:
kubectl apply -k deploy/kubernetes/
```

Out of scope for these templates: managed Postgres/Redis/Rabbit operators, HPA, PDB, cluster-wide gVisor RuntimeClass objects (workers can still set `AGENTBENCH_EXECUTION_MODE=gvisor` when nodes already expose the `runsc` Docker/containerd runtime). NetworkPolicies ship in `networkpolicy.yaml` (see §3c).

## 3b. Helm chart (preferred K8s packaging)

Chart path: `deploy/helm/agentbench/`. Wraps the same API / worker / frontend resources with values-driven images, replicas, resources, optional Ingress, NetworkPolicies, and the same secret pattern. **Mac Docker Compose remains the primary local path.**

```bash
# Lint / template smoke (no cluster):
./scripts/helm/smoke.sh

# Install (replace tags + secrets first):
helm upgrade --install agentbench deploy/helm/agentbench \
  --namespace agentbench --create-namespace \
  --set backend.image.tag=YOUR_TAG \
  --set worker.image.tag=YOUR_TAG \
  --set frontend.image.tag=YOUR_TAG

helm upgrade agentbench deploy/helm/agentbench -n agentbench -f my-values.yaml
helm uninstall agentbench -n agentbench
```

**Dependency strategy:** default is **external** Postgres / Redis / RabbitMQ (`config.*` hosts). Optional `dependencies.*.enabled` spins single-replica lab Deployments only — not HA, not operators. Prefer `secrets.existingSecret` in real clusters.

**Honest limits:** not certified multi-tenant SaaS; no CRDs/operators; NetworkPolicies are included (opt-out); mTLS remains deferred. Postgres WORM/append-only audit landed (`docs/AUDIT_WORM.md`) — not AWS Object Lock. See `deploy/helm/agentbench/README.md` and §3c.

## 3c. NetworkPolicies

Default-deny + allow-needed policies cover API (backend), worker, frontend, and optional in-cluster data-plane pods. They require a CNI that **enforces** NetworkPolicy (Calico, Cilium, Antrea, etc.). On CNIs without enforcement, objects apply but do nothing.

| Direction | Allowed | Denied / residual |
|---|---|---|
| Namespace default | — | All ingress/egress for `app.kubernetes.io/name=agentbench` pods until an allow policy matches |
| DNS | UDP/TCP 53 to cluster DNS | — |
| Backend ingress | TCP 8080 (any source by default; tighten `networkPolicy.ingress.from`) | Other ports |
| Worker ingress | TCP 8081 | Other ports |
| Frontend ingress | TCP 8080 | Other ports; no data-plane egress |
| Backend/worker egress | Postgres 5432, Redis 6379, RabbitMQ 5672; HTTP/HTTPS 80/443 (excl. link-local) | Arbitrary non-HTTP ports; lateral to unrelated pods except via those ports |
| In-cluster Postgres/Redis/Rabbit | Ingress only from backend + worker on data ports | Ingress from other pods; egress from data-plane pods (default-deny) |

**Enable / disable**

```bash
# Helm — on by default
helm upgrade --install agentbench deploy/helm/agentbench -n agentbench \
  --set networkPolicy.enabled=true

# Helm — disable
helm upgrade agentbench deploy/helm/agentbench -n agentbench \
  --set networkPolicy.enabled=false

# Tighter lab stack (in-cluster deps only; no open data-plane ports)
helm upgrade agentbench deploy/helm/agentbench -n agentbench \
  --set dependencies.postgres.enabled=true \
  --set dependencies.redis.enabled=true \
  --set dependencies.rabbitmq.enabled=true \
  --set networkPolicy.egress.allowExternalDataPlane=false

# Raw manifests — included in kustomization; drop networkpolicy.yaml from
# deploy/kubernetes/kustomization.yaml resources to skip.
kubectl apply -k deploy/kubernetes/
```

**Residual risk (honest):** NetworkPolicy is L3/L4 only — not mTLS, not identity-aware authz between services. Audit WORM is Postgres-side (triggers + hash chain), not Object Lock — see `docs/AUDIT_WORM.md`. `allowExternalDataPlane` / `allowWorldHttps` intentionally permit broad destinations on known ports so external managed services and OIDC/S3/LLM calls work; operators should replace those with CIDR/`namespaceSelector` rules via `networkPolicy.egress.extra` and `networkPolicy.ingress.from` before hostile multi-tenant use. Evaluation containers still use Docker `--network none` (or gVisor) independently of these cluster policies.

## 4. Graceful shutdown

1. Load balancer stops sending new traffic (readiness fails or preStop sleep).
2. Process receives SIGTERM; Spring Boot finishes in-flight requests within `AGENTBENCH_SHUTDOWN_TIMEOUT` (default 30s).
3. Worker listeners stop consuming; in-flight stage attempts checkpoint to Postgres; unfinished work remains in outbox / queue for another worker.
4. K8s `terminationGracePeriodSeconds` must exceed shutdown timeout + preStop.

## 5. Related docs

- `docs/DEPLOYMENT_ORACLE_ALWAYS_FREE.md` — Oracle Cloud Always Free Ampere A1 Compose path
- `docs/OPERATIONS.md` — backup / migrate / upgrade / DLQ
- `docs/PERFORMANCE.md` — load-test plan (no unverified throughput claims)
- `docs/SECURITY_THREAT_MODEL.md` — residual isolation limits
- `docs/PORTFOLIO_CHECKLIST.md` — honesty on done vs deferred
