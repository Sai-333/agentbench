package com.agentbench.platform.api;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApiPathsTest {

    @Test
    void orgBaseUsesExplicitOrgIdSegment() {
        assertEquals("/api/v1", ApiPaths.API_V1);
        assertEquals("/api/v1/orgs/{orgId}", ApiPaths.ORG_BASE);
        assertTrue(ApiPaths.ORG_BASE.startsWith(ApiPaths.API_V1));
    }
}
