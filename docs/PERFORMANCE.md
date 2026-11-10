# AgentBench — Performance / load-test plan

**Status:** Milestone 9 planning artifact + **measured local Compose smoke** (see dated section below).  
**Rule:** Do **not** claim production throughput, latency SLOs, or capacity from these laptop numbers. Quote only what a recorded run attaches (console log / summary JSON).

## Goals

1. Confirm health/info endpoints remain healthy under a **light smoke** concurrency.
2. Optionally exercise auth login/register and a few authenticated org GETs under the same smoke.
3. Document how to deepen tests later (queue depth, eval lifecycle) without inventing numbers.

## Smoke script (k6)

Path: `deploy/loadtest/k6-smoke.js`

```bash
# Install k6 separately; not bundled in CI by default.
k6 run -e BASE_URL=http://localhost:8080 deploy/loadtest/k6-smoke.js
```

Defaults: 5 VUs, 30s, thresholds `http_req_failed < 5%`, `p(95) < 2s` for the probe set.

Authenticated path (login + `/me` + org projects/benchmarks/agents/analytics):

```bash
k6 run -e BASE_URL=http://localhost:8080 -e RUN_AUTH=1 \
  -e LOGIN_EMAIL=demo@example.com -e LOGIN_PASSWORD=password123 \
  -e ORG_ID=<uuid> \
  --summary-export=deploy/loadtest/results/measured-summary.json \
  deploy/loadtest/k6-smoke.js | tee deploy/loadtest/results/measured-console.txt
```

Without `LOGIN_EMAIL`, each VU registers a throwaway user (noisier).

## Measured run — 2026-11-10 (local Compose laptop)

Honest **developer Mac** numbers against Sai’s local AgentBench Compose stack. **Not** a production capacity claim.

| Field | Value |
|---|---|
| Date (local) | 2026-11-10 |
| Git SHA | `577619c` (`main` incl. RLS PR #15) |
| Topology | Docker Compose on localhost (`backend:8080`, `frontend:3000`, postgres/redis/rabbitmq/worker healthy) |
| Machine | Apple M1, 8 logical CPUs, 8 GB RAM, macOS 27.0.1 (arm64) |
| Tool | k6 (`/opt/homebrew/bin/k6`) |
| Script | `deploy/loadtest/k6-smoke.js` with `RUN_AUTH=1`, shared login, fixed `ORG_ID` |
| Load | **5 VUs**, **30s** constant |
| Artifacts | `deploy/loadtest/results/measured-2026-11-10-console.txt`, `deploy/loadtest/results/measured-2026-11-10-summary.json` |

### Key metrics (from k6 console summary)

| Metric | Measured |
|---|---|
| HTTP requests | 1375 total · **44.11 req/s** |
| Latency p50 (med) | **7.11 ms** |
| Latency p95 | **132.13 ms** |
| Latency avg / max | 22.1 ms / 500.33 ms |
| Error rate (`http_req_failed`) | **0.00%** (0 / 1375) |
| Checks | 1500/1500 succeeded (readiness, info, login, me, projects, benchmarks, agents, analytics evaluations/overview/costs/health) |
| Thresholds | `http_req_failed < 5%` ✓ · `p(95) < 2s` ✓ |

Endpoints exercised each iteration: `GET /actuator/health/readiness`, `GET /api/v1/info`, `POST /api/v1/auth/login`, `GET /api/v1/me`, then org-scoped `projects`, `benchmarks`, `agents`, `analytics/evaluations`, `analytics/overview`, `analytics/costs`, `analytics/health`.

## What this does **not** measure

- End-to-end evaluation throughput (agent + isolator + suites).
- Hidden-suite concurrency limits under Docker.
- Database saturation or RabbitMQ queue lag under burst publish.
- Multi-node / Oracle Always Free / production CDN behavior.

## Suggested deeper campaign (deferred)

| Scenario | Driver | Signal |
|---|---|---|
| Outbox publish rate | JMeter/k6 custom | unpublished outbox depth |
| Worker drain | N workers × M fake evals | time-to-COMPLETED distribution |
| Isolator contention | parallel docker runs | p95 stage duration + OOM kills |

Record: date, commit SHA, hardware, Compose vs K8s, VU count, raw k6/JMeter summary JSON.

## JMH

Microbenchmarks (scoring pure functions, patch validation) are optional; none are claimed from this smoke. Prefer adding JMH only when a hotspot is identified by a profiler.
