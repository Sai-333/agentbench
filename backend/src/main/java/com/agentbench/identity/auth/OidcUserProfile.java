package com.agentbench.identity.auth;

/**
 * Normalized profile returned after exchanging an authorization code with an IdP.
 */
public record OidcUserProfile(String subject, String email, String displayName) {
}
