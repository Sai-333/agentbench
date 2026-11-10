package com.agentbench.platform.rls;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

/**
 * Applies {@code app.current_org_id} / {@code app.rls_bypass} GUCs before each
 * statement so mid-transaction context switches (org ↔ bypass) take effect
 * even when Spring reuses one pooled connection for the transaction.
 */
final class RlsConnectionSupport {

    private RlsConnectionSupport() {
    }

    static Connection wrap(Connection connection) {
        if (Proxy.isProxyClass(connection.getClass())
                && Proxy.getInvocationHandler(connection) instanceof RlsHandler) {
            return connection;
        }
        return (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(),
                new Class<?>[]{Connection.class},
                new RlsHandler(connection));
    }

    private static final class RlsHandler implements InvocationHandler {
        private final Connection delegate;

        private RlsHandler(Connection delegate) {
            this.delegate = delegate;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            String name = method.getName();
            if ("unwrap".equals(name) && args != null && args.length == 1) {
                Class<?> iface = (Class<?>) args[0];
                if (iface.isInstance(proxy)) {
                    return proxy;
                }
                return delegate.unwrap(iface);
            }
            if ("isWrapperFor".equals(name) && args != null && args.length == 1) {
                Class<?> iface = (Class<?>) args[0];
                return iface.isInstance(proxy) || delegate.isWrapperFor(iface);
            }
            if (shouldApply(name)) {
                // Apply GUCs with a raw statement on the delegate to avoid recursion.
                applyGucsRaw(delegate);
            }
            return method.invoke(delegate, args);
        }

        private static boolean shouldApply(String name) {
            return "prepareStatement".equals(name)
                    || "prepareCall".equals(name)
                    || "createStatement".equals(name)
                    || "nativeSQL".equals(name);
        }

        private static void applyGucsRaw(Connection connection) throws SQLException {
            String product = connection.getMetaData().getDatabaseProductName();
            if (product == null || !product.toLowerCase(java.util.Locale.ROOT).contains("postgresql")) {
                return;
            }
            boolean local = !connection.getAutoCommit();
            UUID orgId = RlsContext.getOrgId();
            boolean bypass = RlsContext.isBypass();
            // Use createStatement on the raw connection (not proxied) to set GUCs.
            try (Statement st = connection.createStatement()) {
                st.execute("SELECT set_config('app.rls_bypass', '" + (bypass ? "on" : "off") + "', " + local + ")");
                String org = orgId == null ? "" : orgId.toString();
                st.execute("SELECT set_config('app.current_org_id', '" + org + "', " + local + ")");
            }
        }
    }
}
