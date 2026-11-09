package com.agentbench.agents.infra;

import com.agentbench.agents.domain.Agent;
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
public class AgentRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public AgentRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Agent insert(
            UUID orgId,
            String name,
            String adapterType,
            UUID secretRefId,
            String configJson,
            UUID createdBy) {
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        jdbc.update("""
                INSERT INTO agents.agents
                    (id, org_id, name, adapter_type, secret_ref_id, config_json, enabled, created_by, created_at, updated_at)
                VALUES
                    (:id, :orgId, :name, :adapterType, :secretRefId, CAST(:configJson AS jsonb), TRUE, :createdBy, :createdAt, :updatedAt)
                """,
                new MapSqlParameterSource()
                        .addValue("id", id)
                        .addValue("orgId", orgId)
                        .addValue("name", name)
                        .addValue("adapterType", adapterType)
                        .addValue("secretRefId", secretRefId)
                        .addValue("configJson", configJson == null ? "{}" : configJson)
                        .addValue("createdBy", createdBy)
                        .addValue("createdAt", Timestamp.from(now))
                        .addValue("updatedAt", Timestamp.from(now)));
        return find(orgId, id).orElseThrow();
    }

    public Optional<Agent> find(UUID orgId, UUID agentId) {
        var list = jdbc.query("""
                SELECT id, org_id, name, adapter_type, secret_ref_id, config_json::text AS config_json,
                       enabled, created_by, created_at, updated_at
                FROM agents.agents
                WHERE org_id = :orgId AND id = :id
                """,
                new MapSqlParameterSource().addValue("orgId", orgId).addValue("id", agentId),
                (rs, rowNum) -> map(rs));
        return list.stream().findFirst();
    }

    public List<Agent> list(UUID orgId) {
        return jdbc.query("""
                SELECT id, org_id, name, adapter_type, secret_ref_id, config_json::text AS config_json,
                       enabled, created_by, created_at, updated_at
                FROM agents.agents
                WHERE org_id = :orgId
                ORDER BY name
                """,
                new MapSqlParameterSource("orgId", orgId),
                (rs, rowNum) -> map(rs));
    }

    public Agent update(
            UUID orgId,
            UUID agentId,
            String name,
            String adapterType,
            UUID secretRefId,
            String configJson,
            boolean enabled) {
        Instant now = Instant.now();
        int updated = jdbc.update("""
                UPDATE agents.agents
                SET name = :name,
                    adapter_type = :adapterType,
                    secret_ref_id = :secretRefId,
                    config_json = CAST(:configJson AS jsonb),
                    enabled = :enabled,
                    updated_at = :updatedAt
                WHERE org_id = :orgId AND id = :id
                """,
                new MapSqlParameterSource()
                        .addValue("orgId", orgId)
                        .addValue("id", agentId)
                        .addValue("name", name)
                        .addValue("adapterType", adapterType)
                        .addValue("secretRefId", secretRefId)
                        .addValue("configJson", configJson == null ? "{}" : configJson)
                        .addValue("enabled", enabled)
                        .addValue("updatedAt", Timestamp.from(now)));
        if (updated == 0) {
            throw new IllegalStateException("Agent not found for update");
        }
        return find(orgId, agentId).orElseThrow();
    }

    public void delete(UUID orgId, UUID agentId) {
        jdbc.update("""
                DELETE FROM agents.agents
                WHERE org_id = :orgId AND id = :id
                """,
                new MapSqlParameterSource()
                        .addValue("orgId", orgId)
                        .addValue("id", agentId));
    }

    private static Agent map(ResultSet rs) throws SQLException {
        return new Agent(
                rs.getObject("id", UUID.class),
                rs.getObject("org_id", UUID.class),
                rs.getString("name"),
                rs.getString("adapter_type"),
                rs.getObject("secret_ref_id", UUID.class),
                rs.getString("config_json"),
                rs.getBoolean("enabled"),
                rs.getObject("created_by", UUID.class),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant()
        );
    }
}
