package com.agentbench.agents.infra;

import com.agentbench.agents.domain.AgentInvocation;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class AgentInvocationRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public AgentInvocationRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public AgentInvocation insertRunning(
            UUID orgId,
            UUID evaluationId,
            UUID agentId,
            UUID taskId,
            String provider,
            String model,
            String prompt,
            int attempt,
            int maxAttempts) {
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        jdbc.update("""
                INSERT INTO agents.agent_invocations
                    (id, org_id, evaluation_id, agent_id, task_id, provider, model, prompt,
                     status, attempt, max_attempts, started_at, created_at)
                VALUES
                    (:id, :orgId, :evaluationId, :agentId, :taskId, :provider, :model, :prompt,
                     'RUNNING', :attempt, :maxAttempts, :startedAt, :createdAt)
                """,
                new MapSqlParameterSource()
                        .addValue("id", id)
                        .addValue("orgId", orgId)
                        .addValue("evaluationId", evaluationId)
                        .addValue("agentId", agentId)
                        .addValue("taskId", taskId)
                        .addValue("provider", provider)
                        .addValue("model", model)
                        .addValue("prompt", prompt)
                        .addValue("attempt", attempt)
                        .addValue("maxAttempts", maxAttempts)
                        .addValue("startedAt", Timestamp.from(now))
                        .addValue("createdAt", Timestamp.from(now)));
        return find(orgId, id).orElseThrow();
    }

    public void complete(
            UUID orgId,
            UUID invocationId,
            String status,
            String providerVersion,
            String model,
            String patchDigest,
            Integer patchByteSize,
            String patchStorageUri,
            Integer promptTokens,
            Integer completionTokens,
            Integer totalTokens,
            BigDecimal estimatedCostUsd,
            String errorCode,
            String errorMessage,
            String metaJson) {
        jdbc.update("""
                UPDATE agents.agent_invocations
                SET status = :status,
                    provider_version = :providerVersion,
                    model = COALESCE(:model, model),
                    finished_at = :finishedAt,
                    patch_digest = :patchDigest,
                    patch_byte_size = :patchByteSize,
                    patch_storage_uri = :patchStorageUri,
                    prompt_tokens = :promptTokens,
                    completion_tokens = :completionTokens,
                    total_tokens = :totalTokens,
                    estimated_cost_usd = :estimatedCostUsd,
                    error_code = :errorCode,
                    error_message = :errorMessage,
                    meta_json = CAST(:metaJson AS jsonb)
                WHERE org_id = :orgId AND id = :id
                """,
                new MapSqlParameterSource()
                        .addValue("orgId", orgId)
                        .addValue("id", invocationId)
                        .addValue("status", status)
                        .addValue("providerVersion", providerVersion)
                        .addValue("model", model)
                        .addValue("finishedAt", Timestamp.from(Instant.now()))
                        .addValue("patchDigest", patchDigest)
                        .addValue("patchByteSize", patchByteSize)
                        .addValue("patchStorageUri", patchStorageUri)
                        .addValue("promptTokens", promptTokens)
                        .addValue("completionTokens", completionTokens)
                        .addValue("totalTokens", totalTokens)
                        .addValue("estimatedCostUsd", estimatedCostUsd)
                        .addValue("errorCode", errorCode)
                        .addValue("errorMessage", errorMessage)
                        .addValue("metaJson", metaJson == null ? "{}" : metaJson));
    }

    public Optional<AgentInvocation> find(UUID orgId, UUID invocationId) {
        var list = jdbc.query("""
                SELECT id, org_id, evaluation_id, agent_id, task_id, provider, model, provider_version,
                       prompt, status, attempt, max_attempts, started_at, finished_at,
                       patch_digest, patch_byte_size, patch_storage_uri,
                       prompt_tokens, completion_tokens, total_tokens, estimated_cost_usd,
                       error_code, error_message, meta_json::text AS meta_json, created_at
                FROM agents.agent_invocations
                WHERE org_id = :orgId AND id = :id
                """,
                new MapSqlParameterSource().addValue("orgId", orgId).addValue("id", invocationId),
                (rs, rowNum) -> map(rs));
        return list.stream().findFirst();
    }

    public List<AgentInvocation> listByEvaluation(UUID orgId, UUID evaluationId) {
        return jdbc.query("""
                SELECT id, org_id, evaluation_id, agent_id, task_id, provider, model, provider_version,
                       prompt, status, attempt, max_attempts, started_at, finished_at,
                       patch_digest, patch_byte_size, patch_storage_uri,
                       prompt_tokens, completion_tokens, total_tokens, estimated_cost_usd,
                       error_code, error_message, meta_json::text AS meta_json, created_at
                FROM agents.agent_invocations
                WHERE org_id = :orgId AND evaluation_id = :evaluationId
                ORDER BY created_at ASC, attempt ASC
                """,
                new MapSqlParameterSource()
                        .addValue("orgId", orgId)
                        .addValue("evaluationId", evaluationId),
                (rs, rowNum) -> map(rs));
    }

    private static AgentInvocation map(ResultSet rs) throws SQLException {
        return new AgentInvocation(
                rs.getObject("id", UUID.class),
                rs.getObject("org_id", UUID.class),
                rs.getObject("evaluation_id", UUID.class),
                rs.getObject("agent_id", UUID.class),
                rs.getObject("task_id", UUID.class),
                rs.getString("provider"),
                rs.getString("model"),
                rs.getString("provider_version"),
                rs.getString("prompt"),
                rs.getString("status"),
                rs.getInt("attempt"),
                rs.getInt("max_attempts"),
                rs.getTimestamp("started_at").toInstant(),
                rs.getTimestamp("finished_at") == null ? null : rs.getTimestamp("finished_at").toInstant(),
                rs.getString("patch_digest"),
                (Integer) rs.getObject("patch_byte_size"),
                rs.getString("patch_storage_uri"),
                (Integer) rs.getObject("prompt_tokens"),
                (Integer) rs.getObject("completion_tokens"),
                (Integer) rs.getObject("total_tokens"),
                rs.getBigDecimal("estimated_cost_usd"),
                rs.getString("error_code"),
                rs.getString("error_message"),
                rs.getString("meta_json"),
                rs.getTimestamp("created_at").toInstant()
        );
    }
}
