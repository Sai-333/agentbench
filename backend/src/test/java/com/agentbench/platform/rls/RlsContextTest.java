package com.agentbench.platform.rls;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RlsContextTest {

    @AfterEach
    void clear() {
        RlsContext.clear();
    }

    @Test
    void nestedOrgAndBypassRestorePreviousScope() {
        UUID orgA = UUID.randomUUID();
        UUID orgB = UUID.randomUUID();
        RlsContext.setOrgId(orgA);
        assertEquals(orgA, RlsContext.getOrgId());
        assertFalse(RlsContext.isBypass());

        RlsContext.callWithBypass(() -> {
            assertTrue(RlsContext.isBypass());
            assertEquals(orgA, RlsContext.getOrgId());
            return null;
        });
        assertFalse(RlsContext.isBypass());

        RlsContext.callWithOrg(orgB, () -> {
            assertEquals(orgB, RlsContext.getOrgId());
            assertFalse(RlsContext.isBypass());
            return null;
        });
        assertEquals(orgA, RlsContext.getOrgId());
    }

    @Test
    void clearRemovesScope() {
        RlsContext.setOrgId(UUID.randomUUID());
        RlsContext.setBypass(true);
        RlsContext.clear();
        assertNull(RlsContext.getOrgId());
        assertFalse(RlsContext.isBypass());
    }
}
