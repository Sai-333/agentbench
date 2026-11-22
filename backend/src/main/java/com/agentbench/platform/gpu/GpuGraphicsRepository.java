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
public class GpuGraphicsRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public GpuGraphicsRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public UUID insert(GpuGraphicsResult result, UUID probedBy, Instant probedAt) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO platform.gpu_graphics_results (
                    id, status, device_name, frames, frame_time_ms, score, score_unit,
                    unavailable_reason, probe_name, detail, probed_at, probed_by
                ) VALUES (
                    :id, :status, :deviceName, :frames, :frameTimeMs, :score, :scoreUnit,
                    :reason, :probeName, :detail, :probedAt, :probedBy
                )
                """,
                new MapSqlParameterSource()
                        .addValue("id", id)
                        .addValue("status", result.status())
                        .addValue("deviceName", result.device(), Types.VARCHAR)
                        .addValue("frames", result.frames(), Types.INTEGER)
                        .addValue("frameTimeMs", result.frameTimeMs(), Types.NUMERIC)
                        .addValue("score", result.score(), Types.NUMERIC)
                        .addValue("scoreUnit", result.scoreUnit(), Types.VARCHAR)
                        .addValue("reason", result.unavailableReason(), Types.VARCHAR)
                        .addValue("probeName", GpuGraphicsResult.PROBE_NAME)
                        .addValue("detail", result.detail(), Types.VARCHAR)
                        .addValue("probedAt", Timestamp.from(probedAt))
                        .addValue("probedBy", probedBy));
        return id;
    }

    public Optional<GpuGraphicsDtos.Result> find(UUID id) {
        List<GpuGraphicsDtos.Result> rows = jdbc.query("""
                SELECT id, status, device_name, frames, frame_time_ms, score, score_unit,
                       unavailable_reason, probe_name, detail, probed_at
                FROM platform.gpu_graphics_results
                WHERE id = :id
                """,
                new MapSqlParameterSource("id", id),
                (rs, rowNum) -> map(rs));
        return rows.stream().findFirst();
    }

    public List<GpuGraphicsDtos.Result> list(int limit) {
        return jdbc.query("""
                SELECT id, status, device_name, frames, frame_time_ms, score, score_unit,
                       unavailable_reason, probe_name, detail, probed_at
                FROM platform.gpu_graphics_results
                ORDER BY probed_at DESC
                LIMIT :limit
                """,
                new MapSqlParameterSource("limit", limit),
                (rs, rowNum) -> map(rs));
    }

    private static GpuGraphicsDtos.Result map(ResultSet rs) throws SQLException {
        int framesRaw = rs.getInt("frames");
        Integer frames = rs.wasNull() ? null : framesRaw;
        return new GpuGraphicsDtos.Result(
                rs.getObject("id", UUID.class),
                rs.getString("status"),
                rs.getString("device_name"),
                frames,
                rs.getBigDecimal("frame_time_ms"),
                rs.getBigDecimal("score"),
                rs.getString("score_unit"),
                rs.getString("unavailable_reason"),
                rs.getString("probe_name"),
                rs.getString("detail"),
                rs.getTimestamp("probed_at").toInstant());
    }
}
