package com.agentbench.identity.auth;

public record AuthCredentials(String providerType, String email, String password) {
}
