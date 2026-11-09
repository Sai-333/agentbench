package com.agentbench.platform.api;

/**
 * Canonical API path conventions for AgentBench.
 *
 * <p>Tenant-scoped product APIs use the explicit org base:
 * {@code /api/v1/orgs/{orgId}/...}. Membership and permission checks apply on every
 * request once identity is implemented (Milestone 2+).
 */
public final class ApiPaths {

    public static final String API_V1 = "/api/v1";
    public static final String ORG_BASE = API_V1 + "/orgs/{orgId}";

    private ApiPaths() {
    }
}
