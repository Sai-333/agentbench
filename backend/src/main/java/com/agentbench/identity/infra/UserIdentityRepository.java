package com.agentbench.identity.infra;

import com.agentbench.identity.domain.UserIdentity;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Repository
public class UserIdentityRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public UserIdentityRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public UserIdentity insert(UUID userId, String provider, String subject, String email) {
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        jdbc.update("""
                INSERT INTO identity.user_identities
                    (id, user_id, provider, subject, email, created_at, updated_at)
                VALUES
                    (:id, :userId, :provider, :subject, :email, :createdAt, :updatedAt)
                """,
                new MapSqlParameterSource()
                        .addValue("id", id)
                        .addValue("userId", userId)
                        .addValue("provider", provider)
                        .addValue("subject", subject)
                        .addValue("email", email)
                        .addValue("createdAt", Timestamp.from(now))
                        .addValue("updatedAt", Timestamp.from(now)));
        return new UserIdentity(id, userId, provider, subject, email, now, now);
    }

    public Optional<UserIdentity> findByProviderAndSubject(String provider, String subject) {
        var list = jdbc.query("""
                SELECT id, user_id, provider, subject, email, created_at, updated_at
                FROM identity.user_identities
                WHERE provider = :provider AND subject = :subject
                """,
                new MapSqlParameterSource()
                        .addValue("provider", provider)
                        .addValue("subject", subject),
                (rs, rowNum) -> map(rs));
        return list.stream().findFirst();
    }

    private static UserIdentity map(ResultSet rs) throws SQLException {
        return new UserIdentity(
                rs.getObject("id", UUID.class),
                rs.getObject("user_id", UUID.class),
                rs.getString("provider"),
                rs.getString("subject"),
                rs.getString("email"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant()
        );
    }
}
