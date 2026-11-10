package com.agentbench.platform.audit;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Repository
public class WormAuditRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public WormAuditRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public WormAuditEntry append(
            UUID orgId,
            String eventType,
            Instant eventTime,
            String actor,
            String resourceType,
            String resourceId,
            String payloadJson) {
        Instant when = eventTime == null ? Instant.now() : eventTime;
        Long id = jdbc.queryForObject("""
                INSERT INTO platform.worm_audit_events
                    (org_id, event_type, event_time, actor, resource_type, resource_id, payload_json)
                VALUES
                    (:orgId, :eventType, :eventTime, :actor, :resourceType, :resourceId, CAST(:payload AS jsonb))
                RETURNING id
                """,
                new MapSqlParameterSource()
                        .addValue("orgId", orgId)
                        .addValue("eventType", eventType)
                        .addValue("eventTime", Timestamp.from(when))
                        .addValue("actor", actor == null ? "system" : actor)
                        .addValue("resourceType", resourceType == null ? "" : resourceType)
                        .addValue("resourceId", resourceId == null ? "" : resourceId)
                        .addValue("payload", payloadJson == null || payloadJson.isBlank() ? "{}" : payloadJson),
                Long.class);
        return findById(id).orElseThrow();
    }

    public java.util.Optional<WormAuditEntry> findById(long id) {
        List<WormAuditEntry> rows = jdbc.query("""
                SELECT id, org_id, event_type, event_time, actor, resource_type, resource_id,
                       payload_json::text AS payload_json, prev_hash, entry_hash, created_at
                FROM platform.worm_audit_events
                WHERE id = :id
                """,
                Map.of("id", id),
                this::mapRow);
        return rows.stream().findFirst();
    }

    public List<WormAuditEntry> listByOrg(UUID orgId, int limit) {
        return jdbc.query("""
                SELECT id, org_id, event_type, event_time, actor, resource_type, resource_id,
                       payload_json::text AS payload_json, prev_hash, entry_hash, created_at
                FROM platform.worm_audit_events
                WHERE org_id = :orgId
                ORDER BY id ASC
                LIMIT :limit
                """,
                new MapSqlParameterSource()
                        .addValue("orgId", orgId)
                        .addValue("limit", Math.max(1, Math.min(limit, 10_000))),
                this::mapRow);
    }

    private WormAuditEntry mapRow(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        Timestamp eventTime = rs.getTimestamp("event_time");
        Timestamp createdAt = rs.getTimestamp("created_at");
        return new WormAuditEntry(
                rs.getLong("id"),
                rs.getObject("org_id", UUID.class),
                rs.getString("event_type"),
                eventTime == null ? null : eventTime.toInstant(),
                rs.getString("actor"),
                rs.getString("resource_type"),
                rs.getString("resource_id"),
                rs.getString("payload_json"),
                rs.getString("prev_hash"),
                rs.getString("entry_hash"),
                createdAt == null ? null : createdAt.toInstant()
        );
    }
}
