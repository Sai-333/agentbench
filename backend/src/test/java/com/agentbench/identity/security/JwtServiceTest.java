package com.agentbench.identity.security;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JwtServiceTest {

    private final JwtService jwtService = new JwtService(
            "test-only-jwt-secret-key-32chars-min!!",
            900,
            3600
    );

    @Test
    void createsAndParsesAccessToken() {
        UUID userId = UUID.randomUUID();
        String token = jwtService.createAccessToken(userId, "user@example.com");
        var parsed = jwtService.parseAccessToken(token);
        assertEquals(userId, parsed.userId());
        assertEquals("user@example.com", parsed.email());
    }

    @Test
    void rejectsGarbageToken() {
        assertThrows(Exception.class, () -> jwtService.parseAccessToken("not.a.jwt"));
    }
}
