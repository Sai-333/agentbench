package com.agentbench.identity.domain;

import java.util.Locale;

/**
 * Organization RBAC roles (Milestone 2).
 * Hierarchy: OWNER &gt; ADMIN &gt; DEVELOPER &gt; VIEWER.
 */
public enum OrgRole {
    OWNER(4),
    ADMIN(3),
    DEVELOPER(2),
    VIEWER(1);

    private final int rank;

    OrgRole(int rank) {
        this.rank = rank;
    }

    public boolean atLeast(OrgRole required) {
        return this.rank >= required.rank;
    }

    public static OrgRole fromString(String value) {
        try {
            return OrgRole.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (Exception ex) {
            throw new IllegalArgumentException("Unknown role: " + value);
        }
    }
}
