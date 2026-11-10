package com.agentbench.identity.auth;

/**
 * Normalized profile extracted from a validated SAML 2.0 assertion.
 */
public record SamlUserProfile(String subject, String email, String displayName) {
}
