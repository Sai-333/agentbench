package com.agentbench.identity.auth;

import java.util.UUID;

public record AuthenticatedPrincipal(UUID userId, String email, String displayName) {
}
