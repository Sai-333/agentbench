package com.agentbench.identity.infra;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Repository
public class RefreshTokenRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public RefreshTokenRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public UUID insert(UUID userId, String tokenHash, Instant expiresAt) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO identity.refresh_tokens (id, user_id, token_hash, expires_at, created_at)
                VALUES (:id, :userId, :tokenHash, :expiresAt, :createdAt)
                """,
                new MapSqlParameterSource()
                        .addValue("id", id)
                        .addValue("userId", userId)
                        .addValue("tokenHash", tokenHash)
                        .addValue("expiresAt", Timestamp.from(expiresAt))
                        .addValue("createdAt", Timestamp.from(Instant.now())));
        return id;
    }

    public Optional<TokenRow> findActiveByHash(String tokenHash) {
        var list = jdbc.query("""
                SELECT id, user_id, token_hash, expires_at, revoked_at
                FROM identity.refresh_tokens
                WHERE token_hash = :tokenHash
                """,
                new MapSqlParameterSource("tokenHash", tokenHash),
                (rs, rowNum) -> new TokenRow(
                        rs.getObject("id", UUID.class),
                        rs.getObject("user_id", UUID.class),
                        rs.getString("token_hash"),
                        rs.getTimestamp("expires_at").toInstant(),
                        rs.getTimestamp("revoked_at") == null ? null : rs.getTimestamp("revoked_at").toInstant()
                ));
        return list.stream()
                .filter(row -> row.revokedAt() == null && row.expiresAt().isAfter(Instant.now()))
                .findFirst();
    }

    public void revokeByHash(String tokenHash) {
        jdbc.update("""
                UPDATE identity.refresh_tokens
                SET revoked_at = :revokedAt
                WHERE token_hash = :tokenHash AND revoked_at IS NULL
                """,
                new MapSqlParameterSource()
                        .addValue("tokenHash", tokenHash)
                        .addValue("revokedAt", Timestamp.from(Instant.now())));
    }

    public void revokeAllForUser(UUID userId) {
        jdbc.update("""
                UPDATE identity.refresh_tokens
                SET revoked_at = :revokedAt
                WHERE user_id = :userId AND revoked_at IS NULL
                """,
                new MapSqlParameterSource()
                        .addValue("userId", userId)
                        .addValue("revokedAt", Timestamp.from(Instant.now())));
    }

    public record TokenRow(UUID id, UUID userId, String tokenHash, Instant expiresAt, Instant revokedAt) {
    }
}
