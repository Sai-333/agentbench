/**
 * AgentBench light smoke load script (k6).
 *
 * Purpose: verify health + info (+ optional auth + authenticated GETs) under a small concurrent load.
 * This is NOT a capacity claim — quote numbers only from a recorded run summary.
 *
 * Prerequisites:
 *   - Compose (or equivalent) running with backend on BASE_URL
 *   - k6 installed: https://k6.io/docs/get-started/installation/
 *
 * Example (anonymous health/info only):
 *   k6 run -e BASE_URL=http://localhost:8080 deploy/loadtest/k6-smoke.js
 *
 * Measured laptop smoke (register/login + a few authenticated GETs):
 *   k6 run -e BASE_URL=http://localhost:8080 -e RUN_AUTH=1 -e VUS=5 -e DURATION=30s \
 *     --summary-export=deploy/loadtest/last-summary.json \
 *     deploy/loadtest/k6-smoke.js
 *
 * Optional shared login (skips per-VU register; uses LOGIN_EMAIL / LOGIN_PASSWORD):
 *   k6 run -e BASE_URL=http://localhost:8080 -e RUN_AUTH=1 \
 *     -e LOGIN_EMAIL=perfdemo@example.com -e LOGIN_PASSWORD=password123 \
 *     deploy/loadtest/k6-smoke.js
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
const JSON_HEADERS = { headers: { "Content-Type": "application/json", Accept: "application/json" } };

function authHeaders(token) {
  return {
    headers: {
      Authorization: `Bearer ${token}`,
      Accept: "application/json",
    },
  };
}

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

  if (__ENV.RUN_AUTH !== "1") {
    sleep(1);
    return;
  }

  let token = null;
  let orgId = __ENV.ORG_ID || "";

  if (__ENV.LOGIN_EMAIL && __ENV.LOGIN_PASSWORD) {
    const login = http.post(
      `${BASE}/api/v1/auth/login`,
      JSON.stringify({
        email: __ENV.LOGIN_EMAIL,
        password: __ENV.LOGIN_PASSWORD,
      }),
      JSON_HEADERS
    );
    check(login, {
      "login is 200": (r) => r.status === 200,
    });
    try {
      token = login.json("accessToken");
    } catch (e) {
      token = null;
    }
  } else {
    const email = `k6-${__VU}-${Date.now()}@example.com`;
    const reg = http.post(
      `${BASE}/api/v1/auth/register`,
      JSON.stringify({
        email,
        password: "password123",
        displayName: `k6-${__VU}`,
      }),
      JSON_HEADERS
    );
    check(reg, {
      "register 201/409": (r) => r.status === 201 || r.status === 409,
    });
    if (reg.status === 201) {
      try {
        token = reg.json("accessToken");
      } catch (e) {
        token = null;
      }
    } else {
      const login = http.post(
        `${BASE}/api/v1/auth/login`,
        JSON.stringify({ email, password: "password123" }),
        JSON_HEADERS
      );
      check(login, { "login after 409 is 200": (r) => r.status === 200 });
      try {
        token = login.json("accessToken");
      } catch (e) {
        token = null;
      }
    }
  }

  if (!token) {
    sleep(1);
    return;
  }

  const me = http.get(`${BASE}/api/v1/me`, authHeaders(token));
  check(me, { "me is 200": (r) => r.status === 200 });

  if (!orgId) {
    try {
      const memberships = me.json("memberships") || [];
      if (memberships.length > 0) {
        orgId = memberships[0].orgId;
      }
    } catch (e) {
      orgId = "";
    }
  }

  if (!orgId) {
    const created = http.post(
      `${BASE}/api/v1/orgs`,
      JSON.stringify({
        name: `k6-org-${__VU}`,
        slug: `k6-org-${__VU}-${Date.now()}`.toLowerCase().replace(/[^a-z0-9-]/g, "").slice(0, 40),
      }),
      {
        headers: {
          Authorization: `Bearer ${token}`,
          "Content-Type": "application/json",
          Accept: "application/json",
        },
      }
    );
    check(created, { "create org 201": (r) => r.status === 201 });
    try {
      orgId = created.json("id");
    } catch (e) {
      orgId = "";
    }
  }

  if (orgId) {
    const projects = http.get(`${BASE}/api/v1/orgs/${orgId}/projects`, authHeaders(token));
    check(projects, { "projects is 200": (r) => r.status === 200 });

    const benchmarks = http.get(`${BASE}/api/v1/orgs/${orgId}/benchmarks`, authHeaders(token));
    check(benchmarks, { "benchmarks is 200": (r) => r.status === 200 });

    const agents = http.get(`${BASE}/api/v1/orgs/${orgId}/agents`, authHeaders(token));
    check(agents, { "agents is 200": (r) => r.status === 200 });

    const evaluations = http.get(
      `${BASE}/api/v1/orgs/${orgId}/analytics/evaluations?page=0&size=10`,
      authHeaders(token)
    );
    check(evaluations, { "analytics evaluations is 200": (r) => r.status === 200 });

    const overview = http.get(`${BASE}/api/v1/orgs/${orgId}/analytics/overview`, authHeaders(token));
    check(overview, { "analytics overview is 200": (r) => r.status === 200 });

    const costs = http.get(`${BASE}/api/v1/orgs/${orgId}/analytics/costs`, authHeaders(token));
    check(costs, { "analytics costs is 200": (r) => r.status === 200 });

    const healthDash = http.get(`${BASE}/api/v1/orgs/${orgId}/analytics/health`, authHeaders(token));
    check(healthDash, { "analytics health is 200": (r) => r.status === 200 });
  }

  sleep(1);
}
