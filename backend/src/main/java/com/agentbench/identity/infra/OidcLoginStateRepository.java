package com.agentbench.identity.infra;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

@Repository
public class OidcLoginStateRepository {

    public record OidcLoginState(String state, String provider, String nonce, String redirectTo, Instant expiresAt) {
    }

    private final NamedParameterJdbcTemplate jdbc;

    public OidcLoginStateRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(String state, String provider, String nonce, String redirectTo, Instant expiresAt) {
        Instant now = Instant.now();
        jdbc.update("""
                INSERT INTO identity.oidc_login_states
                    (state, provider, nonce, redirect_to, expires_at, created_at)
                VALUES
                    (:state, :provider, :nonce, :redirectTo, :expiresAt, :createdAt)
                """,
                new MapSqlParameterSource()
                        .addValue("state", state)
                        .addValue("provider", provider)
                        .addValue("nonce", nonce)
                        .addValue("redirectTo", redirectTo)
                        .addValue("expiresAt", Timestamp.from(expiresAt))
                        .addValue("createdAt", Timestamp.from(now)));
    }

    /**
     * Atomically consume a valid, unexpired state for the given provider.
     */
    public Optional<OidcLoginState> consume(String state, String provider) {
        Instant now = Instant.now();
        var rows = jdbc.query("""
                UPDATE identity.oidc_login_states
                SET consumed_at = :now
                WHERE state = :state
                  AND provider = :provider
                  AND consumed_at IS NULL
                  AND expires_at > :now
                RETURNING state, provider, nonce, redirect_to, expires_at
                """,
                new MapSqlParameterSource()
                        .addValue("state", state)
                        .addValue("provider", provider)
                        .addValue("now", Timestamp.from(now)),
                (rs, rowNum) -> new OidcLoginState(
                        rs.getString("state"),
                        rs.getString("provider"),
                        rs.getString("nonce"),
                        rs.getString("redirect_to"),
                        rs.getTimestamp("expires_at").toInstant()
                ));
        return rows.stream().findFirst();
    }

    /**
     * Consume a SAML RelayState — provider is {@code saml} (env fallback) or {@code saml:<registrationId>}.
     */
    public Optional<OidcLoginState> consumeSaml(String state) {
        Instant now = Instant.now();
        var rows = jdbc.query("""
                UPDATE identity.oidc_login_states
                SET consumed_at = :now
                WHERE state = :state
                  AND (provider = 'saml' OR provider LIKE 'saml:%')
                  AND consumed_at IS NULL
                  AND expires_at > :now
                RETURNING state, provider, nonce, redirect_to, expires_at
                """,
                new MapSqlParameterSource()
                        .addValue("state", state)
                        .addValue("now", Timestamp.from(now)),
                (rs, rowNum) -> new OidcLoginState(
                        rs.getString("state"),
                        rs.getString("provider"),
                        rs.getString("nonce"),
                        rs.getString("redirect_to"),
                        rs.getTimestamp("expires_at").toInstant()
                ));
        return rows.stream().findFirst();
    }

    public int deleteExpired(Instant before) {
        return jdbc.update("""
                DELETE FROM identity.oidc_login_states
                WHERE expires_at < :before OR (consumed_at IS NOT NULL AND consumed_at < :before)
                """,
                new MapSqlParameterSource("before", Timestamp.from(before)));
    }
}
