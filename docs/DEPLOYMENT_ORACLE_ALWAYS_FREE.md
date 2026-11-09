# AgentBench — Deploy on Oracle Cloud Always Free

**Audience:** Operators bringing up a **single-host demo / staging** stack on an Oracle Cloud Infrastructure (OCI) Always Free Ampere A1 VM.  
**Status:** Guide-ready. This document does **not** claim a live public URL — applying it to a running VM requires Sai’s OCI tenancy, instance OCID/public IP, and SSH access.

Canonical Compose behavior is defined in [`docs/DEPLOYMENT.md`](DEPLOYMENT.md), `docker-compose.yml`, and `docker-compose.prod.yml`. This guide only adds the OCI host path.

## What you get

| Piece | Notes |
|---|---|
| Full stack | Postgres 16, Redis 7, RabbitMQ 3.13, backend, worker, frontend |
| Prod-ish overlay | Strong secrets required; graceful shutdown; `restart: always` |
| TLS (recommended) | Cloudflare Tunnel (simplest free HTTPS, no open 80/443) |
| Isolation honesty | Docker-only eval isolation remains lab/demo grade — see portfolio checklist |

## Prerequisites from Sai

Hands-on deploy of a **live** instance needs all of:

1. OCI tenancy with Always Free Ampere capacity available in a chosen region  
2. Instance **OCID** and **public IP** (or private IP + bastion)  
3. SSH access (`opc` or `ubuntu` user + private key)  
4. Optional: a domain on Cloudflare (for Tunnel HTTPS) or any DNS for Caddy/Let’s Encrypt  

Without those, follow this guide as a runbook only — do not publish a fake public URL.

---

## 1. Create an Ampere A1 Always Free VM

### Shape / RAM / disk (full stack)

Always Free Ampere entitlement (per tenancy, shared across A1 VMs): up to **4 OCPUs** and **24 GB RAM**, plus Always Free block storage (typically up to **200 GB** total boot+block across free volumes — confirm in your OCI console).

| Setting | Recommendation for AgentBench full stack | Why |
|---|---|---|
| Shape | `VM.Standard.A1.Flex` | Always Free Ampere |
| OCPUs | **4** | Concurrent Maven/frontend image builds + JVM services |
| Memory | **24 GB** | Postgres + Redis + RabbitMQ + 2 JVMs (backend/worker) + eval containers |
| Boot volume | **100–150 GB** (200 GB if you have headroom) | Images, layers, artifacts volume, Maven cache during build |
| Image | **Canonical Ubuntu 22.04 or 24.04 Minimal aarch64** | First-class Docker Engine packages; Compose plugin |
| VCN | New or existing; public subnet with Internet Gateway for demos | SSH + optional HTTP(S) |
| Public IP | Ephemeral or reserved | Needed for SSH / direct access without bastion |

**Tight alternative:** 2 OCPU / 12 GB can boot the stack for a light demo, but image builds and Docker-backed evals are more likely to OOM or thrash. Prefer the full 4/24 allocation for one VM if you are not splitting Always Free capacity elsewhere.

**Architecture note:** Ampere is **aarch64**. Official Compose images used here (`postgres:16-alpine`, `redis:7-alpine`, `rabbitmq:3.13-management-alpine`, `eclipse-temurin:21-*`) publish arm64 manifests. Multi-arch builder images in this repo are expected to build on aarch64.

### Console steps (short)

1. OCI Console → **Compute → Instances → Create instance**  
2. Name e.g. `agentbench-demo`  
3. Image: Ubuntu 22.04/24.04 aarch64  
4. Shape: Ampere → `VM.Standard.A1.Flex` → 4 OCPU / 24 GB  
5. Networking: public subnet; assign public IPv4  
6. Add SSH public key  
7. Boot volume size ≥ 100 GB  
8. Create → note **public IP** and **OCID**

### Security list / NSG (OCI network)

Open **ingress** on the VCN security list or NSG attached to the instance:

| Port | Proto | Source | Purpose |
|---|---|---|---|
| 22 | TCP | Your IP `/32` (preferred) or `0.0.0.0/0` | SSH |
| 80 | TCP | `0.0.0.0/0` | HTTP (only if using Caddy/nginx on-host) |
| 443 | TCP | `0.0.0.0/0` | HTTPS (only if using Caddy/nginx on-host) |

**Do not** open Postgres `5432`, Redis `6379`, RabbitMQ `5672`/`15672`, or raw app ports `3000`/`8080` to the public Internet for a demo that sits behind a reverse proxy or Tunnel. If you temporarily expose app ports for a private smoke test, restrict source CIDR to your IP and close them afterward.

**Cloudflare Tunnel path:** you can leave **80/443 closed** on the OCI security list; only SSH (and Tunnel outbound) are required.

---

## 2. SSH in and prepare the host

```bash
# From your laptop (replace IP and key):
ssh -i ~/.ssh/your_oci_key ubuntu@<PUBLIC_IP>
# Some Ubuntu OCI images use ubuntu; Oracle Linux uses opc.
```

Optional: clone and run the idempotent bootstrap (Docker Engine + Compose plugin + UFW baseline):

```bash
sudo apt-get update
sudo apt-get install -y git
git clone https://github.com/Sai-333/agentbench.git
cd agentbench
sudo bash scripts/oracle/bootstrap.sh
```

Or install manually (same outcome as the script):

```bash
# Docker Engine + Compose plugin (Ubuntu aarch64)
sudo apt-get update
sudo apt-get install -y ca-certificates curl
sudo install -m 0755 -d /etc/apt/keyrings
curl -fsSL https://download.docker.com/linux/ubuntu/gpg \
  | sudo gpg --dearmor -o /etc/apt/keyrings/docker.gpg
sudo chmod a+r /etc/apt/keyrings/docker.gpg
echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.gpg] \
  https://download.docker.com/linux/ubuntu $(. /etc/os-release && echo "$VERSION_CODENAME") stable" \
  | sudo tee /etc/apt/sources.list.d/docker.list > /dev/null
sudo apt-get update
sudo apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
sudo usermod -aG docker "$USER"
# Log out/in (or newgrp docker) before compose without sudo
```

**Docker 29 note:** if Testcontainers or older clients fail with API negotiation errors, set a lower minimum API (same as root README):

```bash
echo '{"min-api-version":"1.24"}' | sudo tee /etc/docker/daemon.json
sudo systemctl restart docker
```

---

## 3. Clone, secrets, Compose up

```bash
git clone https://github.com/Sai-333/agentbench.git
cd agentbench
cp .env.example .env
```

Edit `.env` and **replace** at least:

| Variable | Requirement |
|---|---|
| `POSTGRES_PASSWORD` | Strong random secret |
| `RABBITMQ_PASSWORD` | Strong random secret |
| `AGENTBENCH_JWT_SECRET` | ≥ 32 characters |
| `AGENTBENCH_SECRETS_ENCRYPTION_KEY` | Base64 of 32 random bytes |

Generate secrets on the VM (do not commit `.env`):

```bash
# Example generators — paste results into .env
openssl rand -base64 24   # passwords
openssl rand -base64 48   # JWT secret (≥32 chars after decode/use as opaque string)
openssl rand -base64 32   # AGENTBENCH_SECRETS_ENCRYPTION_KEY
```

**Prod-ish bring-up** (recommended on OCI):

```bash
docker compose -f docker-compose.yml -f docker-compose.prod.yml up -d --build
```

Dev/demo bring-up (weaker defaults; fine only on private hosts):

```bash
docker compose up -d --build
```

First build on Ampere can take several minutes (Maven + frontend). Watch:

```bash
docker compose -f docker-compose.yml -f docker-compose.prod.yml ps
docker compose -f docker-compose.yml -f docker-compose.prod.yml logs -f --tail=100
```

### Optional: Docker-backed eval isolation on the worker

Compose documents an optional `/var/run/docker.sock` mount **only on the worker** (never into evaluation containers). Uncomment that volume in `docker-compose.yml` on a trusted controller host if you need `RuntimeIsolator` Docker mode against the host engine. See [`docs/DEPLOYMENT.md`](DEPLOYMENT.md).

### Bind ports vs public exposure

Default Compose publishes:

| Host port (env) | Service | Public? |
|---|---|---|
| `FRONTEND_PORT` (3000) | frontend | Prefer proxy/Tunnel only |
| `BACKEND_PORT` (8080) | backend / Swagger | Prefer proxy/Tunnel only |
| `POSTGRES_PORT` (5432) | postgres | **No** — localhost or Docker network |
| `REDIS_PORT` (6379) | redis | **No** |
| `RABBITMQ_PORT` (5672) | rabbitmq | **No** |
| `RABBITMQ_MANAGEMENT_PORT` (15672) | RabbitMQ UI | **No** (or SSH tunnel) |

For a tighter host, you can rebind published ports to `127.0.0.1` in an override file so only localhost (and Tunnel/Caddy on the host) can reach them — left as an operator choice; the stock files publish `0.0.0.0` for local demos.

---

## 4. Health checks

Run on the VM (or via SSH tunnel). Matches Compose healthchecks and [`docs/DEPLOYMENT.md`](DEPLOYMENT.md):

```bash
# Infra
docker compose exec postgres pg_isready -U agentbench -d agentbench
docker compose exec redis redis-cli ping
docker compose exec rabbitmq rabbitmq-diagnostics -q ping

# Apps (default published ports)
curl -sf http://127.0.0.1:8080/actuator/health/liveness
curl -sf http://127.0.0.1:8080/actuator/health/readiness
curl -sf http://127.0.0.1:8080/api/v1/info
curl -sf http://127.0.0.1:8080/v3/api-docs | head -c 200
curl -sf -o /dev/null -w '%{http_code}\n' http://127.0.0.1:8080/swagger-ui.html
curl -sf http://127.0.0.1:3000/ | head

# Or use the helper (secret-free):
bash scripts/oracle/healthcheck.sh
```

| Probe | URL |
|---|---|
| Liveness | `http://127.0.0.1:8080/actuator/health/liveness` |
| Readiness | `http://127.0.0.1:8080/actuator/health/readiness` |
| API info | `http://127.0.0.1:8080/api/v1/info` |
| Swagger UI | `http://127.0.0.1:8080/swagger-ui.html` |
| OpenAPI JSON | `http://127.0.0.1:8080/v3/api-docs` |
| Frontend | `http://127.0.0.1:3000/` |

From a laptop without opening app ports:

```bash
ssh -L 8080:127.0.0.1:8080 -L 3000:127.0.0.1:3000 ubuntu@<PUBLIC_IP>
# then open http://localhost:3000 and http://localhost:8080/swagger-ui.html
```

---

## 5. TLS options (pick one)

### Recommended (simplest free): Cloudflare Tunnel

**Why:** Free HTTPS, no OCI ingress for 80/443, no cert renewal on the VM, works with ephemeral public IPs.

**Tradeoffs:** Depends on Cloudflare; origin is not directly reachable on 443; free Tunnel is plenty for demos but is not a full WAF/enterprise edge.

1. Create a Tunnel in Cloudflare Zero Trust → install `cloudflared` on the VM (aarch64 package).  
2. Route hostname(s) to `http://127.0.0.1:3000` (UI) and optionally `http://127.0.0.1:8080` (API/Swagger), or put a tiny Caddy/nginx locally that routes `/api` and `/swagger-ui` / `/v3` / `/actuator` to the backend and `/` to the frontend, then point the Tunnel at that single origin.  
3. Keep Postgres/Redis/RabbitMQ off the Tunnel.

Exact `cloudflared` install commands change with Cloudflare’s packaging — follow their current Linux ARM64 docs after creating the Tunnel token. Do **not** commit tunnel tokens to git.

### Alternative: Caddy on the VM + Let’s Encrypt

**Why:** Automatic HTTPS with a real domain pointing at the instance public IP.

**Tradeoffs:** Requires opening **80/443** on OCI security lists **and** host firewall; needs a stable DNS A record; cert issuance fails if ports are blocked.

Minimal idea: install Caddy, reverse-proxy to `127.0.0.1:3000` / `127.0.0.1:8080`, let Caddy obtain certificates. Nginx + certbot is equivalent but more steps.

### Not recommended for demos

- Exposing `:3000` / `:8080` in cleartext on `0.0.0.0/0`  
- Terminating TLS only at Cloudflare “Flexible” while origin stays HTTP on the public Internet without Tunnel  

---

## 6. Host firewall

OCI security lists are not enough — configure the OS firewall too.

### UFW (Ubuntu — used by `scripts/oracle/bootstrap.sh`)

```bash
sudo ufw default deny incoming
sudo ufw default allow outgoing
sudo ufw allow OpenSSH
# Only if using on-host Caddy/nginx (skip for Cloudflare Tunnel-only):
# sudo ufw allow 80/tcp
# sudo ufw allow 443/tcp
sudo ufw --force enable
sudo ufw status
```

### firewalld (Oracle Linux / some images)

```bash
sudo systemctl enable --now firewalld
sudo firewall-cmd --permanent --add-service=ssh
# sudo firewall-cmd --permanent --add-service=http
# sudo firewall-cmd --permanent --add-service=https
sudo firewall-cmd --reload
```

### iptables (legacy / recovery)

Prefer `ufw` or `firewalld` wrappers. If you must:

```bash
# Illustrative only — persist with iptables-persistent or your distro’s tool
sudo iptables -A INPUT -m state --state ESTABLISHED,RELATED -j ACCEPT
sudo iptables -A INPUT -p tcp --dport 22 -j ACCEPT
# sudo iptables -A INPUT -p tcp --dport 80 -j ACCEPT
# sudo iptables -A INPUT -p tcp --dport 443 -j ACCEPT
sudo iptables -P INPUT DROP
```

---

## 7. Updates

```bash
cd ~/agentbench   # or your clone path
git pull origin main
docker compose -f docker-compose.yml -f docker-compose.prod.yml up -d --build
bash scripts/oracle/healthcheck.sh
```

- **Flyway:** forward-only; never edit applied migrations (`docs/DEPLOYMENT.md`, `docs/OPERATIONS.md`).  
- **Secrets:** rotate by editing `.env` and recreating affected services; do not commit `.env`.  
- **Backups:** volume backup notes live in `docs/OPERATIONS.md` (Postgres named volume).  
- **Rollback:** `git checkout <previous-sha>` then recreate compose; DB migrations are not automatically reversible.

---

## 8. Exact command sequence (checklist)

On a fresh Ubuntu Always Free A1 VM, after SSH:

```bash
sudo apt-get update && sudo apt-get install -y git
git clone https://github.com/Sai-333/agentbench.git
cd agentbench
sudo bash scripts/oracle/bootstrap.sh
# log out/in so docker group applies, then:
cd ~/agentbench   # adjust if clone path differs
cp .env.example .env
# edit .env — set POSTGRES_PASSWORD, RABBITMQ_PASSWORD,
# AGENTBENCH_JWT_SECRET, AGENTBENCH_SECRETS_ENCRYPTION_KEY
docker compose -f docker-compose.yml -f docker-compose.prod.yml up -d --build
bash scripts/oracle/healthcheck.sh
```

Then either:

- SSH tunnels for private access, or  
- Cloudflare Tunnel (recommended) / Caddy for public HTTPS  

---

## 9. Honesty / non-claims

| Claim | Status |
|---|---|
| Guide for OCI Always Free Compose | **Yes** — this document + `scripts/oracle/` |
| Live public demo URL operated by this PR | **No** — needs Sai’s VM + SSH |
| Hostile multi-tenant SaaS / gVisor | **Not claimed** |
| Measured throughput on Ampere | **Not claimed** without attached runs |

Related: [`docs/DEPLOYMENT.md`](DEPLOYMENT.md), [`docs/OPERATIONS.md`](OPERATIONS.md), [`docs/PORTFOLIO_CHECKLIST.md`](PORTFOLIO_CHECKLIST.md), root [README](../README.md).
