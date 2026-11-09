package com.agentbench.identity.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

@Service
public class JwtService {

    private final SecretKey key;
    private final long accessTtlSeconds;
    private final long refreshTtlSeconds;

    public JwtService(
            @Value("${agentbench.jwt.secret}") String secret,
            @Value("${agentbench.jwt.access-ttl-seconds:900}") long accessTtlSeconds,
            @Value("${agentbench.jwt.refresh-ttl-seconds:604800}") long refreshTtlSeconds) {
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 32) {
            throw new IllegalStateException("agentbench.jwt.secret must be at least 32 characters");
        }
        this.key = Keys.hmacShaKeyFor(bytes);
        this.accessTtlSeconds = accessTtlSeconds;
        this.refreshTtlSeconds = refreshTtlSeconds;
    }

    public String createAccessToken(UUID userId, String email) {
        Instant now = Instant.now();
        return Jwts.builder()
                .id(UUID.randomUUID().toString())
                .subject(userId.toString())
                .claim("email", email)
                .claim("typ", "access")
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(accessTtlSeconds)))
                .signWith(key)
                .compact();
    }

    public String createRefreshTokenValue() {
        return UUID.randomUUID() + "." + UUID.randomUUID();
    }

    public long refreshTtlSeconds() {
        return refreshTtlSeconds;
    }

    public long accessTtlSeconds() {
        return accessTtlSeconds;
    }

    public ParsedAccessToken parseAccessToken(String token) {
        Claims claims = Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
        if (!"access".equals(claims.get("typ", String.class))) {
            throw new IllegalArgumentException("Not an access token");
        }
        return new ParsedAccessToken(
                UUID.fromString(claims.getSubject()),
                claims.get("email", String.class),
                claims.getExpiration().toInstant()
        );
    }

    public record ParsedAccessToken(UUID userId, String email, Instant expiresAt) {
    }
}
