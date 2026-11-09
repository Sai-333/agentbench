package com.agentbench.identity.infra;

import com.agentbench.identity.domain.SecretRef;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class SecretRefRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public SecretRefRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public SecretRef insert(UUID orgId, String name, byte[] ciphertext, int keyVersion, String purpose) {
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        jdbc.update("""
                INSERT INTO identity.secret_refs
                    (id, org_id, name, ciphertext, key_version, purpose, created_at)
                VALUES
                    (:id, :orgId, :name, :ciphertext, :keyVersion, :purpose, :createdAt)
                """,
                new MapSqlParameterSource()
                        .addValue("id", id)
                        .addValue("orgId", orgId)
                        .addValue("name", name)
                        .addValue("ciphertext", ciphertext)
                        .addValue("keyVersion", keyVersion)
                        .addValue("purpose", purpose)
                        .addValue("createdAt", Timestamp.from(now)));
        return new SecretRef(id, orgId, name, ciphertext, keyVersion, purpose, now, null);
    }

    public Optional<SecretRef> findById(UUID orgId, UUID secretId) {
        var list = jdbc.query("""
                SELECT id, org_id, name, ciphertext, key_version, purpose, created_at, rotated_at
                FROM identity.secret_refs
                WHERE org_id = :orgId AND id = :id
                """,
                new MapSqlParameterSource()
                        .addValue("orgId", orgId)
                        .addValue("id", secretId),
                (rs, rowNum) -> mapSecret(rs));
        return list.stream().findFirst();
    }

    public List<SecretRef> listByOrg(UUID orgId) {
        return jdbc.query("""
                SELECT id, org_id, name, ciphertext, key_version, purpose, created_at, rotated_at
                FROM identity.secret_refs
                WHERE org_id = :orgId
                ORDER BY name
                """,
                new MapSqlParameterSource("orgId", orgId),
                (rs, rowNum) -> mapSecret(rs));
    }

    public void rotate(UUID orgId, UUID secretId, byte[] ciphertext, int keyVersion) {
        jdbc.update("""
                UPDATE identity.secret_refs
                SET ciphertext = :ciphertext, key_version = :keyVersion, rotated_at = :rotatedAt
                WHERE org_id = :orgId AND id = :id
                """,
                new MapSqlParameterSource()
                        .addValue("orgId", orgId)
                        .addValue("id", secretId)
                        .addValue("ciphertext", ciphertext)
                        .addValue("keyVersion", keyVersion)
                        .addValue("rotatedAt", Timestamp.from(Instant.now())));
    }

    public void delete(UUID orgId, UUID secretId) {
        jdbc.update("""
                DELETE FROM identity.secret_refs
                WHERE org_id = :orgId AND id = :id
                """,
                new MapSqlParameterSource()
                        .addValue("orgId", orgId)
                        .addValue("id", secretId));
    }

    private static SecretRef mapSecret(ResultSet rs) throws SQLException {
        return new SecretRef(
                rs.getObject("id", UUID.class),
                rs.getObject("org_id", UUID.class),
                rs.getString("name"),
                rs.getBytes("ciphertext"),
                rs.getInt("key_version"),
                rs.getString("purpose"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("rotated_at") == null ? null : rs.getTimestamp("rotated_at").toInstant()
        );
    }
}
