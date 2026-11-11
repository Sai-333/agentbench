package com.agentbench.evaluation.infra;

import com.agentbench.evaluation.domain.FlakeQuarantine;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class FlakeQuarantineRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public FlakeQuarantineRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<FlakeQuarantine> listActive(UUID orgId) {
        return jdbc.query("""
                SELECT id, org_id, suite_id, case_key, reason, active, created_by_user_id,
                       created_at, updated_at, expires_at
                FROM evaluations.flake_quarantines
                WHERE org_id = :orgId
                  AND active = TRUE
                  AND (expires_at IS NULL OR expires_at > now())
                ORDER BY created_at DESC
                """,
                new MapSqlParameterSource("orgId", orgId),
                (rs, rowNum) -> map(rs));
    }

    public List<FlakeQuarantine> listAll(UUID orgId, boolean includeInactive) {
        return jdbc.query(listSql(includeInactive), new MapSqlParameterSource("orgId", orgId), (rs, rowNum) -> map(rs));
    }

    /**
     * Active-only SQL must keep whitespace before ORDER BY. A text block drops the
     * newline after its opening delimiter, which previously produced {@code TRUEORDER}.
     */
    public static String listSql(boolean includeInactive) {
        return """
                SELECT id, org_id, suite_id, case_key, reason, active, created_by_user_id,
                       created_at, updated_at, expires_at
                FROM evaluations.flake_quarantines
                WHERE org_id = :orgId
                """
                + (includeInactive ? "" : " AND active = TRUE")
                + """

                ORDER BY created_at DESC
                """;
    }

    public Optional<FlakeQuarantine> find(UUID orgId, UUID id) {
        var list = jdbc.query("""
                SELECT id, org_id, suite_id, case_key, reason, active, created_by_user_id,
                       created_at, updated_at, expires_at
                FROM evaluations.flake_quarantines
                WHERE org_id = :orgId AND id = :id
                """,
                new MapSqlParameterSource()
                        .addValue("orgId", orgId)
                        .addValue("id", id),
                (rs, rowNum) -> map(rs));
        return list.stream().findFirst();
    }

    public FlakeQuarantine insert(FlakeQuarantine row) {
        jdbc.update("""
                INSERT INTO evaluations.flake_quarantines
                    (id, org_id, suite_id, case_key, reason, active, created_by_user_id,
                     created_at, updated_at, expires_at)
                VALUES
                    (:id, :orgId, :suiteId, :caseKey, :reason, :active, :createdBy,
                     :createdAt, :updatedAt, :expiresAt)
                """,
                params(row));
        return row;
    }

    public void deactivate(UUID orgId, UUID id) {
        jdbc.update("""
                UPDATE evaluations.flake_quarantines
                SET active = FALSE, updated_at = now()
                WHERE org_id = :orgId AND id = :id
                """,
                new MapSqlParameterSource()
                        .addValue("orgId", orgId)
                        .addValue("id", id));
    }

    private static MapSqlParameterSource params(FlakeQuarantine row) {
        return new MapSqlParameterSource()
                .addValue("id", row.id())
                .addValue("orgId", row.orgId())
                .addValue("suiteId", row.suiteId())
                .addValue("caseKey", blankToNull(row.caseKey()))
                .addValue("reason", row.reason() == null ? "" : row.reason())
                .addValue("active", row.active())
                .addValue("createdBy", row.createdByUserId())
                .addValue("createdAt", Timestamp.from(row.createdAt()))
                .addValue("updatedAt", Timestamp.from(row.updatedAt()))
                .addValue("expiresAt", row.expiresAt() == null ? null : Timestamp.from(row.expiresAt()));
    }

    private static FlakeQuarantine map(ResultSet rs) throws SQLException {
        Timestamp expires = rs.getTimestamp("expires_at");
        return new FlakeQuarantine(
                rs.getObject("id", UUID.class),
                rs.getObject("org_id", UUID.class),
                rs.getObject("suite_id", UUID.class),
                rs.getString("case_key"),
                rs.getString("reason"),
                rs.getBoolean("active"),
                rs.getObject("created_by_user_id", UUID.class),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant(),
                expires == null ? null : expires.toInstant()
        );
    }

    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
