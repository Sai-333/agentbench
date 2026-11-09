package com.agentbench.identity.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OrgRoleTest {

    @Test
    void hierarchyAllowsOwnerOverViewer() {
        assertTrue(OrgRole.OWNER.atLeast(OrgRole.VIEWER));
        assertTrue(OrgRole.ADMIN.atLeast(OrgRole.DEVELOPER));
        assertTrue(OrgRole.DEVELOPER.atLeast(OrgRole.VIEWER));
        assertFalse(OrgRole.VIEWER.atLeast(OrgRole.DEVELOPER));
        assertFalse(OrgRole.DEVELOPER.atLeast(OrgRole.ADMIN));
    }
}
