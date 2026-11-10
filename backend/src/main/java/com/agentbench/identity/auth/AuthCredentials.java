package com.agentbench.identity.auth;

public record AuthCredentials(
        String providerType,
        String email,
        String password,
        String authorizationCode
) {
    public AuthCredentials(String providerType, String email, String password) {
        this(providerType, email, password, null);
    }
}
