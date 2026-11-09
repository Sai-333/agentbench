package com.agentbench.identity.security;

import org.springframework.security.core.AuthenticatedPrincipal;

import java.util.UUID;

public record UserPrincipal(UUID userId, String email) implements AuthenticatedPrincipal {

    @Override
    public String getName() {
        return userId.toString();
    }
}
