package com.agentbench.platform.rls;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * Thread-local tenant / bypass flags applied to PostgreSQL session GUCs
 * ({@code app.current_org_id}, {@code app.rls_bypass}) before JDBC statements.
 *
 * <p>HTTP org routes set the org id via {@link OrgRlsFilter}. Workers set org
 * from {@code EvaluationCommand} or briefly enable bypass for cross-tenant
 * infrastructure scans (outbox drain, timeout sweep, slug uniqueness).
 */
public final class RlsContext {

    private static final Scope EMPTY = new Scope(null, false);
    private static final ThreadLocal<Scope> CURRENT = ThreadLocal.withInitial(() -> EMPTY);

    private RlsContext() {
    }

    public static UUID getOrgId() {
        return CURRENT.get().orgId();
    }

    public static boolean isBypass() {
        return CURRENT.get().bypass();
    }

    public static void setOrgId(UUID orgId) {
        Scope cur = CURRENT.get();
        CURRENT.set(new Scope(orgId, cur.bypass()));
    }

    public static void setBypass(boolean bypass) {
        Scope cur = CURRENT.get();
        CURRENT.set(new Scope(cur.orgId(), bypass));
    }

    public static void clear() {
        CURRENT.remove();
    }

    public static void runWithOrg(UUID orgId, Runnable action) {
        callWithOrg(orgId, () -> {
            action.run();
            return null;
        });
    }

    public static <T> T callWithOrg(UUID orgId, Supplier<T> action) {
        Scope previous = CURRENT.get();
        CURRENT.set(new Scope(orgId, false));
        try {
            return action.get();
        } finally {
            restore(previous);
        }
    }

    public static void runWithBypass(Runnable action) {
        callWithBypass(() -> {
            action.run();
            return null;
        });
    }

    public static <T> T callWithBypass(Supplier<T> action) {
        Scope previous = CURRENT.get();
        CURRENT.set(new Scope(previous.orgId(), true));
        try {
            return action.get();
        } finally {
            restore(previous);
        }
    }

    private static void restore(Scope previous) {
        if (previous == null || previous == EMPTY) {
            CURRENT.remove();
        } else {
            CURRENT.set(previous);
        }
    }

    private record Scope(UUID orgId, boolean bypass) {
    }
}
