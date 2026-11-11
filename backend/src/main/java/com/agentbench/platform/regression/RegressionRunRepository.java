package com.agentbench.platform.regression;

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
public class RegressionRunRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public RegressionRunRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Inserts the run and its cases. Passed/failed/skipped counts are computed
     * from the case rows so a report summary cannot be stored on its own.
     */
    public UUID insert(
            String gitRepo,
            String gitBranch,
            String gitSha,
            String suiteName,
            Instant startedAt,
            Instant finishedAt,
            UUID ingestedBy,
            List<RegressionReportParser.ParsedCase> cases) {
        int passed = 0;
        int failed = 0;
        int skipped = 0;
        for (RegressionReportParser.ParsedCase item : cases) {
            switch (item.status()) {
                case PASSED -> passed++;
                case FAILED -> failed++;
                case SKIPPED -> skipped++;
            }
        }
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO platform.regression_runs (
                    id, git_repo, git_branch, git_sha, suite_name,
                    started_at, finished_at, passed_count, failed_count, skipped_count, ingested_by
                ) VALUES (
                    :id, :gitRepo, :gitBranch, :gitSha, :suiteName,
                    :startedAt, :finishedAt, :passed, :failed, :skipped, :ingestedBy
                )
                """,
                new MapSqlParameterSource()
                        .addValue("id", id)
                        .addValue("gitRepo", gitRepo)
                        .addValue("gitBranch", gitBranch)
                        .addValue("gitSha", gitSha)
                        .addValue("suiteName", suiteName)
                        .addValue("startedAt", Timestamp.from(startedAt))
                        .addValue("finishedAt", Timestamp.from(finishedAt))
                        .addValue("passed", passed)
                        .addValue("failed", failed)
                        .addValue("skipped", skipped)
                        .addValue("ingestedBy", ingestedBy));
        int position = 0;
        for (RegressionReportParser.ParsedCase item : cases) {
            jdbc.update("""
                    INSERT INTO platform.regression_cases (
                        id, run_id, name, status, duration_ms, message, position
                    ) VALUES (
                        :id, :runId, :name, :status, :durationMs, :message, :position
                    )
                    """,
                    new MapSqlParameterSource()
                            .addValue("id", UUID.randomUUID())
                            .addValue("runId", id)
                            .addValue("name", item.name())
                            .addValue("status", item.status().wire())
                            .addValue("durationMs", item.durationMs())
                            .addValue("message", item.message())
                            .addValue("position", position));
            position++;
        }
        return id;
    }

    public List<RegressionDtos.RunSummary> list(int limit) {
        return jdbc.query("""
                SELECT id, git_repo, git_branch, git_sha, suite_name, started_at, finished_at,
                       passed_count, failed_count, skipped_count, ingested_by
                FROM platform.regression_runs
                ORDER BY started_at DESC, created_at DESC
                LIMIT :limit
                """,
                new MapSqlParameterSource("limit", limit),
                (rs, rowNum) -> mapSummary(rs));
    }

    public Optional<RegressionDtos.RunDetail> find(UUID id) {
        List<RegressionDtos.RunSummary> rows = jdbc.query("""
                SELECT id, git_repo, git_branch, git_sha, suite_name, started_at, finished_at,
                       passed_count, failed_count, skipped_count, ingested_by
                FROM platform.regression_runs
                WHERE id = :id
                """,
                new MapSqlParameterSource("id", id),
                (rs, rowNum) -> mapSummary(rs));
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        RegressionDtos.RunSummary summary = rows.get(0);
        List<RegressionDtos.CaseResponse> cases = jdbc.query("""
                SELECT name, status, duration_ms, message
                FROM platform.regression_cases
                WHERE run_id = :id
                ORDER BY position ASC
                """,
                new MapSqlParameterSource("id", id),
                (rs, rowNum) -> new RegressionDtos.CaseResponse(
                        rs.getString("name"),
                        rs.getString("status"),
                        rs.getLong("duration_ms"),
                        rs.getString("message")));
        return Optional.of(new RegressionDtos.RunDetail(
                summary.id(),
                summary.gitRepo(),
                summary.gitBranch(),
                summary.gitSha(),
                summary.suiteName(),
                summary.startedAt(),
                summary.finishedAt(),
                summary.passed(),
                summary.failed(),
                summary.skipped(),
                summary.caseCount(),
                summary.scope(),
                summary.ingestedBy(),
                List.copyOf(cases)));
    }

    private static RegressionDtos.RunSummary mapSummary(ResultSet rs) throws SQLException {
        int passed = rs.getInt("passed_count");
        int failed = rs.getInt("failed_count");
        int skipped = rs.getInt("skipped_count");
        return new RegressionDtos.RunSummary(
                rs.getObject("id", UUID.class),
                rs.getString("git_repo"),
                rs.getString("git_branch"),
                rs.getString("git_sha"),
                rs.getString("suite_name"),
                rs.getTimestamp("started_at").toInstant(),
                rs.getTimestamp("finished_at").toInstant(),
                passed,
                failed,
                skipped,
                passed + failed + skipped,
                RegressionDtos.SCOPE,
                rs.getObject("ingested_by", UUID.class));
    }
}
