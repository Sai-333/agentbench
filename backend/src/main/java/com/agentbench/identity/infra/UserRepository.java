package com.agentbench.identity.infra;

import com.agentbench.identity.domain.UserAccount;
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
public class UserRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public UserRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public UserAccount insert(String email, String passwordHash, String displayName) {
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        jdbc.update("""
                INSERT INTO identity.users (id, email, password_hash, display_name, created_at, updated_at)
                VALUES (:id, :email, :passwordHash, :displayName, :createdAt, :updatedAt)
                """,
                new MapSqlParameterSource()
                        .addValue("id", id)
                        .addValue("email", email)
                        .addValue("passwordHash", passwordHash)
                        .addValue("displayName", displayName)
                        .addValue("createdAt", Timestamp.from(now))
                        .addValue("updatedAt", Timestamp.from(now)));
        return new UserAccount(id, email, passwordHash, displayName, now, now);
    }

    public Optional<UserAccount> findByEmail(String email) {
        var list = jdbc.query("""
                SELECT id, email, password_hash, display_name, created_at, updated_at
                FROM identity.users WHERE email = :email
                """,
                new MapSqlParameterSource("email", email),
                (rs, rowNum) -> mapUser(rs));
        return list.stream().findFirst();
    }

    public Optional<UserAccount> findById(UUID id) {
        var list = jdbc.query("""
                SELECT id, email, password_hash, display_name, created_at, updated_at
                FROM identity.users WHERE id = :id
                """,
                new MapSqlParameterSource("id", id),
                (rs, rowNum) -> mapUser(rs));
        return list.stream().findFirst();
    }

    private static UserAccount mapUser(ResultSet rs) throws SQLException {
        return new UserAccount(
                rs.getObject("id", UUID.class),
                rs.getString("email"),
                rs.getString("password_hash"),
                rs.getString("display_name"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant()
        );
    }
}
