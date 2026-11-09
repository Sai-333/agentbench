/**
 * AgentBench light smoke load script (k6).
 *
 * Purpose: verify health + auth + info stay responsive under a small concurrent load.
 * This is NOT a capacity claim — do not quote throughput numbers without a recorded run.
 *
 * Prerequisites:
 *   - Compose (or equivalent) running with backend on BASE_URL
 *   - k6 installed: https://k6.io/docs/get-started/installation/
 *
 * Example:
 *   k6 run -e BASE_URL=http://localhost:8080 deploy/loadtest/k6-smoke.js
 *
 * Optional authenticated path (register each VU once):
 *   k6 run -e BASE_URL=http://localhost:8080 -e RUN_AUTH=1 deploy/loadtest/k6-smoke.js
 */
import http from "k6/http";
import { check, sleep } from "k6";

export const options = {
  scenarios: {
    smoke: {
      executor: "constant-vus",
      vus: Number(__ENV.VUS || 5),
      duration: __ENV.DURATION || "30s",
    },
  },
  thresholds: {
    http_req_failed: ["rate<0.05"],
    http_req_duration: ["p(95)<2000"],
  },
};

const BASE = (__ENV.BASE_URL || "http://localhost:8080").replace(/\/$/, "");

export default function () {
  const health = http.get(`${BASE}/actuator/health/readiness`);
  check(health, {
    "readiness is 200": (r) => r.status === 200,
  });

  const info = http.get(`${BASE}/api/v1/info`);
  check(info, {
    "info is 200": (r) => r.status === 200,
    "info milestone present": (r) => String(r.body || "").includes("milestone"),
  });

  if (__ENV.RUN_AUTH === "1") {
    const email = `k6-${__VU}-${Date.now()}@example.com`;
    const reg = http.post(
      `${BASE}/api/v1/auth/register`,
      JSON.stringify({
        email,
        password: "password123",
        displayName: `k6-${__VU}`,
      }),
      { headers: { "Content-Type": "application/json" } }
    );
    check(reg, {
      "register 201/409": (r) => r.status === 201 || r.status === 409,
    });
  }

  sleep(1);
}
