package com.agentbench.platform.gpu;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class GpuBenchmarkRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public GpuBenchmarkRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public UUID insert(GpuBenchmarkResult result, UUID probedBy, Instant probedAt) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO platform.gpu_benchmark_results (
                    id, status, device_name, api_name, score, score_unit,
                    unavailable_reason, probe_name, detail, probed_at, probed_by
                ) VALUES (
                    :id, :status, :deviceName, :apiName, :score, :scoreUnit,
                    :reason, :probeName, :detail, :probedAt, :probedBy
                )
                """,
                new MapSqlParameterSource()
                        .addValue("id", id)
                        .addValue("status", result.status())
                        .addValue("deviceName", result.device(), Types.VARCHAR)
                        .addValue("apiName", result.api(), Types.VARCHAR)
                        .addValue("score", result.score(), Types.NUMERIC)
                        .addValue("scoreUnit", result.scoreUnit(), Types.VARCHAR)
                        .addValue("reason", result.unavailableReason(), Types.VARCHAR)
                        .addValue("probeName", GpuBenchmarkResult.PROBE_NAME)
                        .addValue("detail", result.detail(), Types.VARCHAR)
                        .addValue("probedAt", Timestamp.from(probedAt))
                        .addValue("probedBy", probedBy));
        return id;
    }

    public Optional<GpuBenchmarkDtos.Result> find(UUID id) {
        List<GpuBenchmarkDtos.Result> rows = jdbc.query("""
                SELECT id, status, device_name, api_name, score, score_unit,
                       unavailable_reason, probe_name, detail, probed_at
                FROM platform.gpu_benchmark_results
                WHERE id = :id
                """,
                new MapSqlParameterSource("id", id),
                (rs, rowNum) -> map(rs));
        return rows.stream().findFirst();
    }

    public List<GpuBenchmarkDtos.Result> list(int limit) {
        return jdbc.query("""
                SELECT id, status, device_name, api_name, score, score_unit,
                       unavailable_reason, probe_name, detail, probed_at
                FROM platform.gpu_benchmark_results
                ORDER BY probed_at DESC
                LIMIT :limit
                """,
                new MapSqlParameterSource("limit", limit),
                (rs, rowNum) -> map(rs));
    }

    private static GpuBenchmarkDtos.Result map(ResultSet rs) throws SQLException {
        return new GpuBenchmarkDtos.Result(
                rs.getObject("id", UUID.class),
                rs.getString("status"),
                rs.getString("device_name"),
                rs.getString("api_name"),
                rs.getBigDecimal("score"),
                rs.getString("score_unit"),
                rs.getString("unavailable_reason"),
                rs.getString("probe_name"),
                rs.getString("detail"),
                rs.getTimestamp("probed_at").toInstant());
    }
}
