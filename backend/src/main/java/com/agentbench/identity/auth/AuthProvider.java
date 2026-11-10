package com.agentbench.identity.auth;

/**
 * Extensible authentication provider SPI.
 * Milestone 2 ships {@link LocalPasswordAuthProvider}; Google/GitHub OIDC plug in beside it
 * without changing JWT issuance.
 */
public interface AuthProvider {

    String id();

    boolean supports(String providerType);

    /**
     * Whether this provider is active for login (config present). Local password is always enabled.
     */
    default boolean enabled() {
        return true;
    }

    AuthenticatedPrincipal authenticate(AuthCredentials credentials);
}
