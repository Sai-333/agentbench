# AgentBench — Performance / load-test plan

**Status:** Milestone 9 planning artifact.  
**Rule:** Do **not** claim throughput, latency SLOs, or capacity without a measured run attached (CI artifact, local log, or dashboard screenshot).

## Goals

1. Confirm health/info endpoints remain healthy under a **light smoke** concurrency.
2. Optionally exercise auth register under the same smoke (noisy; off by default).
3. Document how to deepen tests later (queue depth, eval lifecycle) without inventing numbers now.

## Smoke script (k6)

Path: `deploy/loadtest/k6-smoke.js`

```bash
# Install k6 separately; not bundled in CI by default.
k6 run -e BASE_URL=http://localhost:8080 deploy/loadtest/k6-smoke.js
```

Defaults: 5 VUs, 30s, thresholds `http_req_failed < 5%`, `p(95) < 2s` for the tiny probe set.

Optional auth path:

```bash
k6 run -e BASE_URL=http://localhost:8080 -e RUN_AUTH=1 deploy/loadtest/k6-smoke.js
```

## What this does **not** measure

- End-to-end evaluation throughput (agent + isolator + suites).
- Hidden-suite concurrency limits under Docker.
- Database saturation or RabbitMQ queue lag under burst publish.

## Suggested deeper campaign (deferred)

| Scenario | Driver | Signal |
|---|---|---|
| Outbox publish rate | JMeter/k6 custom | unpublished outbox depth |
| Worker drain | N workers × M fake evals | time-to-COMPLETED distribution |
| Isolator contention | parallel docker runs | p95 stage duration + OOM kills |

Record: date, commit SHA, hardware, Compose vs K8s, VU count, raw k6/JMeter summary JSON.

## JMH

Microbenchmarks (scoring pure functions, patch validation) are optional; none are claimed in M9. Prefer adding JMH only when a hotspot is identified by a profiler.
