package com.agentbench.identity.auth;

/**
 * Extensible authentication provider SPI. Milestone 2 ships {@link LocalPasswordAuthProvider}.
 * OIDC / SSO providers can plug in later without changing JWT issuance.
 */
public interface AuthProvider {

    String id();

    boolean supports(String providerType);

    AuthenticatedPrincipal authenticate(AuthCredentials credentials);
}
