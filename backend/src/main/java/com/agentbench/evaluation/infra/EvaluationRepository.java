package com.agentbench.evaluation.infra;

import com.agentbench.evaluation.domain.CaseCategory;
import com.agentbench.evaluation.domain.Evaluation;
import com.agentbench.evaluation.domain.EvaluationScore;
import com.agentbench.evaluation.domain.EvaluationState;
import com.agentbench.evaluation.domain.EvaluationTransition;
import com.agentbench.evaluation.domain.FailureClass;
import com.agentbench.evaluation.domain.OrgEvaluationLimits;
import com.agentbench.evaluation.domain.PatchArtifact;
import com.agentbench.evaluation.domain.QualityReport;
import com.agentbench.evaluation.domain.ScoreMetric;
import com.agentbench.evaluation.domain.ScoreStatus;
import com.agentbench.evaluation.domain.ScoringProfile;
import com.agentbench.evaluation.domain.TestCaseResult;
import com.agentbench.evaluation.domain.TestCaseStatus;
import com.agentbench.evaluation.domain.TestPhase;
import com.agentbench.evaluation.domain.TestRun;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
public class EvaluationRepository {

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public EvaluationRepository(NamedParameterJdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public OrgEvaluationLimits getOrDefaultLimits(UUID orgId) {
        var list = jdbc.query("""
                SELECT org_id, max_concurrent_evaluations, max_queued_evaluations, max_concurrent_agent_calls,
                       max_duration_sec, max_patch_bytes, max_log_artifact_bytes
                FROM evaluations.org_limits
                WHERE org_id = :orgId
                """,
                new MapSqlParameterSource("orgId", orgId),
                (rs, rowNum) -> new OrgEvaluationLimits(
                        rs.getObject("org_id", UUID.class),
                        rs.getInt("max_concurrent_evaluations"),
                        rs.getInt("max_queued_evaluations"),
                        rs.getInt("max_concurrent_agent_calls"),
                        rs.getInt("max_duration_sec"),
                        rs.getInt("max_patch_bytes"),
                        rs.getInt("max_log_artifact_bytes")
                ));
        return list.stream().findFirst().orElse(OrgEvaluationLimits.defaults(orgId));
    }

    public int countByStates(UUID orgId, List<String> states) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM evaluations.evaluations
                WHERE org_id = :orgId AND state IN (:states)
                """,
                new MapSqlParameterSource()
                        .addValue("orgId", orgId)
                        .addValue("states", states),
                Integer.class);
        return count == null ? 0 : count;
    }

    public Optional<Evaluation> findByIdempotencyKey(UUID orgId, String key) {
        if (key == null || key.isBlank()) {
            return Optional.empty();
        }
        var list = jdbc.query("""
                SELECT * FROM evaluations.evaluations
                WHERE org_id = :orgId AND idempotency_key = :key
                """,
                new MapSqlParameterSource().addValue("orgId", orgId).addValue("key", key),
                (rs, rowNum) -> mapEvaluation(rs));
        return list.stream().findFirst();
    }

    public Evaluation insert(Evaluation evaluation) {
        jdbc.update("""
                INSERT INTO evaluations.evaluations
                    (id, org_id, benchmark_version_id, task_id, agent_id, state, attempt,
                     failure_class, error_code, error_message, idempotency_key, snapshot_json,
                     workspace_uri, cancel_requested, created_by, created_at, updated_at, started_at, completed_at)
                VALUES
                    (:id, :orgId, :benchmarkVersionId, :taskId, :agentId, :state, :attempt,
                     :failureClass, :errorCode, :errorMessage, :idempotencyKey, CAST(:snapshotJson AS jsonb),
                     :workspaceUri, :cancelRequested, :createdBy, :createdAt, :updatedAt, :startedAt, :completedAt)
                """,
                params(evaluation));
        return evaluation;
    }

    public Optional<Evaluation> find(UUID orgId, UUID evaluationId) {
        var list = jdbc.query("""
                SELECT * FROM evaluations.evaluations
                WHERE org_id = :orgId AND id = :id
                """,
                new MapSqlParameterSource().addValue("orgId", orgId).addValue("id", evaluationId),
                (rs, rowNum) -> mapEvaluation(rs));
        return list.stream().findFirst();
    }

    public Optional<Evaluation> findById(UUID evaluationId) {
        var list = jdbc.query("""
                SELECT * FROM evaluations.evaluations WHERE id = :id
                """,
                new MapSqlParameterSource("id", evaluationId),
                (rs, rowNum) -> mapEvaluation(rs));
        return list.stream().findFirst();
    }

    public List<Evaluation> list(UUID orgId, String stateFilter, UUID agentId, UUID taskId, int limit) {
        StringBuilder sql = new StringBuilder("""
                SELECT * FROM evaluations.evaluations
                WHERE org_id = :orgId
                """);
        MapSqlParameterSource params = new MapSqlParameterSource("orgId", orgId);
        if (stateFilter != null && !stateFilter.isBlank()) {
            sql.append(" AND state = :state");
            params.addValue("state", stateFilter);
        }
        if (agentId != null) {
            sql.append(" AND agent_id = :agentId");
            params.addValue("agentId", agentId);
        }
        if (taskId != null) {
            sql.append(" AND task_id = :taskId");
            params.addValue("taskId", taskId);
        }
        sql.append(" ORDER BY created_at DESC LIMIT :limit");
        params.addValue("limit", Math.min(Math.max(limit, 1), 200));
        return jdbc.query(sql.toString(), params, (rs, rowNum) -> mapEvaluation(rs));
    }

    public void updateLifecycle(
            UUID orgId,
            UUID evaluationId,
            EvaluationState state,
            int attempt,
            FailureClass failureClass,
            String errorCode,
            String errorMessage,
            String workspaceUri,
            boolean cancelRequested,
            Instant startedAt,
            Instant completedAt) {
        jdbc.update("""
                UPDATE evaluations.evaluations
                SET state = :state,
                    attempt = :attempt,
                    failure_class = :failureClass,
                    error_code = :errorCode,
                    error_message = :errorMessage,
                    workspace_uri = COALESCE(:workspaceUri, workspace_uri),
                    cancel_requested = :cancelRequested,
                    started_at = COALESCE(:startedAt, started_at),
                    completed_at = :completedAt,
                    updated_at = :updatedAt
                WHERE org_id = :orgId AND id = :id
                """,
                new MapSqlParameterSource()
                        .addValue("orgId", orgId)
                        .addValue("id", evaluationId)
                        .addValue("state", state.name())
                        .addValue("attempt", attempt)
                        .addValue("failureClass", failureClass == null ? null : failureClass.name())
                        .addValue("errorCode", errorCode)
                        .addValue("errorMessage", errorMessage)
                        .addValue("workspaceUri", workspaceUri)
                        .addValue("cancelRequested", cancelRequested)
                        .addValue("startedAt", startedAt == null ? null : Timestamp.from(startedAt))
                        .addValue("completedAt", completedAt == null ? null : Timestamp.from(completedAt))
                        .addValue("updatedAt", Timestamp.from(Instant.now())));
    }

    public void markCancelRequested(UUID orgId, UUID evaluationId) {
        jdbc.update("""
                UPDATE evaluations.evaluations
                SET cancel_requested = TRUE, updated_at = :updatedAt
                WHERE org_id = :orgId AND id = :id
                """,
                new MapSqlParameterSource()
                        .addValue("orgId", orgId)
                        .addValue("id", evaluationId)
                        .addValue("updatedAt", Timestamp.from(Instant.now())));
    }

    public EvaluationTransition insertTransition(
            UUID evaluationId,
            UUID orgId,
            EvaluationState from,
            EvaluationState to,
            String reason,
            String actor,
            int attempt) {
        GeneratedKeyHolder keyHolder = new GeneratedKeyHolder();
        Instant now = Instant.now();
        jdbc.getJdbcTemplate().update(connection -> {
            var ps = connection.prepareStatement("""
                    INSERT INTO evaluations.evaluation_state_transitions
                        (evaluation_id, org_id, from_state, to_state, reason, actor, attempt, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """, new String[]{"id"});
            ps.setObject(1, evaluationId);
            ps.setObject(2, orgId);
            ps.setString(3, from == null ? null : from.name());
            ps.setString(4, to.name());
            ps.setString(5, reason == null ? "" : reason);
            ps.setString(6, actor == null ? "system" : actor);
            ps.setInt(7, attempt);
            ps.setTimestamp(8, Timestamp.from(now));
            return ps;
        }, keyHolder);
        long id = keyHolder.getKey() == null ? -1L : keyHolder.getKey().longValue();
        return new EvaluationTransition(id, evaluationId, orgId, from, to, reason, actor, attempt, now);
    }

    public List<EvaluationTransition> listTransitions(UUID orgId, UUID evaluationId) {
        return jdbc.query("""
                SELECT id, evaluation_id, org_id, from_state, to_state, reason, actor, attempt, created_at
                FROM evaluations.evaluation_state_transitions
                WHERE org_id = :orgId AND evaluation_id = :evaluationId
                ORDER BY created_at ASC, id ASC
                """,
                new MapSqlParameterSource()
                        .addValue("orgId", orgId)
                        .addValue("evaluationId", evaluationId),
                (rs, rowNum) -> new EvaluationTransition(
                        rs.getLong("id"),
                        rs.getObject("evaluation_id", UUID.class),
                        rs.getObject("org_id", UUID.class),
                        rs.getString("from_state") == null ? null : EvaluationState.fromString(rs.getString("from_state")),
                        EvaluationState.fromString(rs.getString("to_state")),
                        rs.getString("reason"),
                        rs.getString("actor"),
                        rs.getInt("attempt"),
                        rs.getTimestamp("created_at").toInstant()
                ));
    }

    public boolean tryClaimStage(UUID evaluationId, UUID orgId, EvaluationState state, int attempt, String messageId) {
        try {
            int updated = jdbc.update("""
                    INSERT INTO evaluations.evaluation_stage_attempts
                        (id, evaluation_id, org_id, state, attempt, message_id, status, started_at)
                    VALUES
                        (:id, :evaluationId, :orgId, :state, :attempt, :messageId, 'STARTED', :startedAt)
                    """,
                    new MapSqlParameterSource()
                            .addValue("id", UUID.randomUUID())
                            .addValue("evaluationId", evaluationId)
                            .addValue("orgId", orgId)
                            .addValue("state", state.name())
                            .addValue("attempt", attempt)
                            .addValue("messageId", messageId)
                            .addValue("startedAt", Timestamp.from(Instant.now())));
            return updated == 1;
        } catch (org.springframework.dao.DuplicateKeyException ex) {
            return false;
        }
    }

    public boolean isMessageProcessed(String messageId) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM evaluations.processed_messages WHERE message_id = :messageId
                """,
                new MapSqlParameterSource("messageId", messageId),
                Integer.class);
        return count != null && count > 0;
    }

    public void markMessageProcessed(String messageId, UUID evaluationId, UUID orgId) {
        jdbc.update("""
                INSERT INTO evaluations.processed_messages (message_id, evaluation_id, org_id, processed_at)
                VALUES (:messageId, :evaluationId, :orgId, :processedAt)
                ON CONFLICT (message_id) DO NOTHING
                """,
                new MapSqlParameterSource()
                        .addValue("messageId", messageId)
                        .addValue("evaluationId", evaluationId)
                        .addValue("orgId", orgId)
                        .addValue("processedAt", Timestamp.from(Instant.now())));
    }

    public void completeStageAttempt(UUID evaluationId, EvaluationState state, int attempt, String status,
                                     String errorCode, String errorMessage) {
        jdbc.update("""
                UPDATE evaluations.evaluation_stage_attempts
                SET status = :status, finished_at = :finishedAt, error_code = :errorCode, error_message = :errorMessage
                WHERE evaluation_id = :evaluationId AND state = :state AND attempt = :attempt
                """,
                new MapSqlParameterSource()
                        .addValue("evaluationId", evaluationId)
                        .addValue("state", state.name())
                        .addValue("attempt", attempt)
                        .addValue("status", status)
                        .addValue("finishedAt", Timestamp.from(Instant.now()))
                        .addValue("errorCode", errorCode)
                        .addValue("errorMessage", errorMessage));
    }

    public void insertOutbox(UUID orgId, UUID aggregateId, String eventType, String payloadJson) {
        jdbc.update("""
                INSERT INTO evaluations.outbox_events
                    (aggregate_type, aggregate_id, org_id, event_type, payload, created_at)
                VALUES
                    ('evaluation', :aggregateId, :orgId, :eventType, CAST(:payload AS jsonb), :createdAt)
                """,
                new MapSqlParameterSource()
                        .addValue("aggregateId", aggregateId)
                        .addValue("orgId", orgId)
                        .addValue("eventType", eventType)
                        .addValue("payload", payloadJson)
                        .addValue("createdAt", Timestamp.from(Instant.now())));
    }

    public List<OutboxRow> listUnpublished(int limit) {
        return jdbc.query("""
                SELECT id, aggregate_id, org_id, event_type, payload::text AS payload, publish_attempts
                FROM evaluations.outbox_events
                WHERE published_at IS NULL
                ORDER BY id ASC
                LIMIT :limit
                """,
                new MapSqlParameterSource("limit", limit),
                (rs, rowNum) -> new OutboxRow(
                        rs.getLong("id"),
                        rs.getObject("aggregate_id", UUID.class),
                        rs.getObject("org_id", UUID.class),
                        rs.getString("event_type"),
                        rs.getString("payload"),
                        rs.getInt("publish_attempts")
                ));
    }

    public void markPublished(long id) {
        jdbc.update("""
                UPDATE evaluations.outbox_events
                SET published_at = :publishedAt
                WHERE id = :id
                """,
                new MapSqlParameterSource()
                        .addValue("id", id)
                        .addValue("publishedAt", Timestamp.from(Instant.now())));
    }

    public void incrementPublishAttempts(long id) {
        jdbc.update("""
                UPDATE evaluations.outbox_events
                SET publish_attempts = publish_attempts + 1
                WHERE id = :id
                """,
                new MapSqlParameterSource("id", id));
    }

    public PatchArtifact insertPatch(PatchArtifact patch) {
        jdbc.update("""
                INSERT INTO evaluations.patch_artifacts
                    (id, evaluation_id, org_id, content_digest, storage_uri, format, byte_size, collected_at, sealed, purged_at)
                VALUES
                    (:id, :evaluationId, :orgId, :contentDigest, :storageUri, :format, :byteSize, :collectedAt, :sealed, :purgedAt)
                """,
                new MapSqlParameterSource()
                        .addValue("id", patch.id())
                        .addValue("evaluationId", patch.evaluationId())
                        .addValue("orgId", patch.orgId())
                        .addValue("contentDigest", patch.contentDigest())
                        .addValue("storageUri", patch.storageUri())
                        .addValue("format", patch.format())
                        .addValue("byteSize", patch.byteSize())
                        .addValue("collectedAt", Timestamp.from(patch.collectedAt()))
                        .addValue("sealed", patch.sealed())
                        .addValue("purgedAt", patch.purgedAt() == null ? null : Timestamp.from(patch.purgedAt())));
        return patch;
    }

    public Optional<PatchArtifact> findPatch(UUID evaluationId) {
        var list = jdbc.query("""
                SELECT id, evaluation_id, org_id, content_digest, storage_uri, format, byte_size, collected_at, sealed, purged_at
                FROM evaluations.patch_artifacts
                WHERE evaluation_id = :evaluationId
                """,
                new MapSqlParameterSource("evaluationId", evaluationId),
                (rs, rowNum) -> {
                    Timestamp purged = rs.getTimestamp("purged_at");
                    return new PatchArtifact(
                            rs.getObject("id", UUID.class),
                            rs.getObject("evaluation_id", UUID.class),
                            rs.getObject("org_id", UUID.class),
                            rs.getString("content_digest"),
                            rs.getString("storage_uri"),
                            rs.getString("format"),
                            rs.getInt("byte_size"),
                            rs.getTimestamp("collected_at").toInstant(),
                            rs.getBoolean("sealed"),
                            purged == null ? null : purged.toInstant()
                    );
                });
        return list.stream().findFirst();
    }

    public TestRun insertTestRun(TestRun run) {
        jdbc.update("""
                INSERT INTO evaluations.test_runs
                    (id, evaluation_id, org_id, phase, suite_id, status, started_at, finished_at, container_meta)
                VALUES
                    (:id, :evaluationId, :orgId, :phase, :suiteId, :status, :startedAt, :finishedAt, CAST(:containerMeta AS jsonb))
                """,
                new MapSqlParameterSource()
                        .addValue("id", run.id())
                        .addValue("evaluationId", run.evaluationId())
                        .addValue("orgId", run.orgId())
                        .addValue("phase", run.phase().name())
                        .addValue("suiteId", run.suiteId())
                        .addValue("status", run.status())
                        .addValue("startedAt", Timestamp.from(run.startedAt()))
                        .addValue("finishedAt", run.finishedAt() == null ? null : Timestamp.from(run.finishedAt()))
                        .addValue("containerMeta", run.containerMetaJson() == null ? "{}" : run.containerMetaJson()));
        return run;
    }

    public void finishTestRun(UUID testRunId, String status, Instant finishedAt) {
        jdbc.update("""
                UPDATE evaluations.test_runs
                SET status = :status, finished_at = :finishedAt
                WHERE id = :id
                """,
                new MapSqlParameterSource()
                        .addValue("id", testRunId)
                        .addValue("status", status)
                        .addValue("finishedAt", Timestamp.from(finishedAt)));
    }

    public Optional<TestRun> findTestRun(UUID evaluationId, TestPhase phase) {
        var list = jdbc.query("""
                SELECT id, evaluation_id, org_id, phase, suite_id, status, started_at, finished_at, container_meta::text AS container_meta
                FROM evaluations.test_runs
                WHERE evaluation_id = :evaluationId AND phase = :phase
                """,
                new MapSqlParameterSource()
                        .addValue("evaluationId", evaluationId)
                        .addValue("phase", phase.name()),
                (rs, rowNum) -> mapTestRun(rs));
        return list.stream().findFirst();
    }

    public void insertTestCaseResult(TestCaseResult result) {
        jdbc.update("""
                INSERT INTO evaluations.test_case_results
                    (id, test_run_id, org_id, case_key, status, duration_ms, transition, message, log_excerpt_uri,
                     category, flaky, attempt_count, meta_json)
                VALUES
                    (:id, :testRunId, :orgId, :caseKey, :status, :durationMs, :transition, :message, :logExcerptUri,
                     :category, :flaky, :attemptCount, CAST(:metaJson AS jsonb))
                """,
                new MapSqlParameterSource()
                        .addValue("id", result.id())
                        .addValue("testRunId", result.testRunId())
                        .addValue("orgId", result.orgId())
                        .addValue("caseKey", result.caseKey())
                        .addValue("status", result.status().name())
                        .addValue("durationMs", result.durationMs())
                        .addValue("transition", result.transition())
                        .addValue("message", result.message() == null ? "" : result.message())
                        .addValue("logExcerptUri", result.logExcerptUri())
                        .addValue("category", result.category() == null ? null : result.category().name())
                        .addValue("flaky", result.flaky())
                        .addValue("attemptCount", result.attemptCount() <= 0 ? 1 : result.attemptCount())
                        .addValue("metaJson", result.metaJson() == null || result.metaJson().isBlank() ? "{}" : result.metaJson()));
    }

    public List<TestCaseResult> listTestCaseResults(UUID testRunId) {
        return jdbc.query("""
                SELECT id, test_run_id, org_id, case_key, status, duration_ms, transition, message, log_excerpt_uri,
                       category, flaky, attempt_count, meta_json::text AS meta_json
                FROM evaluations.test_case_results
                WHERE test_run_id = :testRunId
                ORDER BY case_key
                """,
                new MapSqlParameterSource("testRunId", testRunId),
                (rs, rowNum) -> mapTestCaseResult(rs));
    }

    public List<TestRun> listTestRuns(UUID evaluationId) {
        return jdbc.query("""
                SELECT id, evaluation_id, org_id, phase, suite_id, status, started_at, finished_at, container_meta::text AS container_meta
                FROM evaluations.test_runs
                WHERE evaluation_id = :evaluationId
                ORDER BY started_at ASC
                """,
                new MapSqlParameterSource("evaluationId", evaluationId),
                (rs, rowNum) -> mapTestRun(rs));
    }

    public void insertFlakyAttempt(
            UUID evaluationId,
            UUID orgId,
            UUID testRunId,
            TestPhase phase,
            int attemptIndex,
            String caseKey,
            TestCaseStatus status) {
        jdbc.update("""
                INSERT INTO evaluations.flaky_detection_attempts
                    (id, evaluation_id, org_id, test_run_id, phase, attempt_index, case_key, status)
                VALUES
                    (:id, :evaluationId, :orgId, :testRunId, :phase, :attemptIndex, :caseKey, :status)
                """,
                new MapSqlParameterSource()
                        .addValue("id", UUID.randomUUID())
                        .addValue("evaluationId", evaluationId)
                        .addValue("orgId", orgId)
                        .addValue("testRunId", testRunId)
                        .addValue("phase", phase.name())
                        .addValue("attemptIndex", attemptIndex)
                        .addValue("caseKey", caseKey)
                        .addValue("status", status.name()));
    }

    public Optional<ScoringProfile> findScoringProfile(String profileKey) {
        var list = jdbc.query("""
                SELECT id, profile_key, display_name, description, weights_json::text AS weights_json,
                       flaky_repeat_runs, active, created_at
                FROM evaluations.scoring_profiles
                WHERE profile_key = :profileKey AND active = TRUE
                """,
                new MapSqlParameterSource("profileKey", profileKey),
                (rs, rowNum) -> mapScoringProfile(rs));
        return list.stream().findFirst();
    }

    public QualityReport insertQualityReport(QualityReport report) {
        jdbc.update("""
                INSERT INTO evaluations.quality_reports
                    (id, evaluation_id, org_id, tool, metrics_json, normalized_score, created_at)
                VALUES
                    (:id, :evaluationId, :orgId, :tool, CAST(:metricsJson AS jsonb), :normalizedScore, :createdAt)
                """,
                new MapSqlParameterSource()
                        .addValue("id", report.id())
                        .addValue("evaluationId", report.evaluationId())
                        .addValue("orgId", report.orgId())
                        .addValue("tool", report.tool())
                        .addValue("metricsJson", report.metricsJson() == null ? "{}" : report.metricsJson())
                        .addValue("normalizedScore", report.normalizedScore())
                        .addValue("createdAt", Timestamp.from(report.createdAt())));
        return report;
    }

    public List<QualityReport> findQualityReports(UUID evaluationId) {
        return jdbc.query("""
                SELECT id, evaluation_id, org_id, tool, metrics_json::text AS metrics_json, normalized_score, created_at
                FROM evaluations.quality_reports
                WHERE evaluation_id = :evaluationId
                ORDER BY created_at ASC
                """,
                new MapSqlParameterSource("evaluationId", evaluationId),
                (rs, rowNum) -> new QualityReport(
                        rs.getObject("id", UUID.class),
                        rs.getObject("evaluation_id", UUID.class),
                        rs.getObject("org_id", UUID.class),
                        rs.getString("tool"),
                        rs.getString("metrics_json"),
                        rs.getBigDecimal("normalized_score"),
                        rs.getTimestamp("created_at").toInstant()
                ));
    }

    public void insertScore(EvaluationScore score, List<ScoreMetric> metrics) {
        jdbc.update("""
                INSERT INTO evaluations.evaluation_scores
                    (id, evaluation_id, org_id, weighted_total, profile_id, profile_key, score_status,
                     transition_summary_json, raw_summary_json, warnings_json, computed_at)
                VALUES
                    (:id, :evaluationId, :orgId, :weightedTotal, :profileId, :profileKey, :scoreStatus,
                     CAST(:transitionSummary AS jsonb), CAST(:rawSummary AS jsonb), CAST(:warnings AS jsonb), :computedAt)
                """,
                new MapSqlParameterSource()
                        .addValue("id", score.id())
                        .addValue("evaluationId", score.evaluationId())
                        .addValue("orgId", score.orgId())
                        .addValue("weightedTotal", score.weightedTotal())
                        .addValue("profileId", score.profileId() == null ? "default-v1" : score.profileId())
                        .addValue("profileKey", score.profileKey())
                        .addValue("scoreStatus", score.scoreStatus().name())
                        .addValue("transitionSummary", score.transitionSummaryJson())
                        .addValue("rawSummary", score.rawSummaryJson())
                        .addValue("warnings", score.warningsJson())
                        .addValue("computedAt", Timestamp.from(score.computedAt())));
        for (ScoreMetric metric : metrics) {
            jdbc.update("""
                    INSERT INTO evaluations.score_metrics
                        (id, evaluation_score_id, metric_key, raw_value, weight, weighted_value, meta_json)
                    VALUES
                        (:id, :scoreId, :metricKey, :rawValue, :weight, :weightedValue, CAST(:metaJson AS jsonb))
                    """,
                    new MapSqlParameterSource()
                            .addValue("id", metric.id())
                            .addValue("scoreId", metric.evaluationScoreId())
                            .addValue("metricKey", metric.metricKey())
                            .addValue("rawValue", metric.rawValue())
                            .addValue("weight", metric.weight())
                            .addValue("weightedValue", metric.weightedValue())
                            .addValue("metaJson", metric.metaJson() == null ? "{}" : metric.metaJson()));
        }
    }

    public Optional<EvaluationScore> findScore(UUID evaluationId) {
        var list = jdbc.query("""
                SELECT id, evaluation_id, org_id, weighted_total, profile_id, profile_key, score_status,
                       transition_summary_json::text AS transition_summary_json,
                       raw_summary_json::text AS raw_summary_json,
                       warnings_json::text AS warnings_json,
                       computed_at
                FROM evaluations.evaluation_scores
                WHERE evaluation_id = :evaluationId
                """,
                new MapSqlParameterSource("evaluationId", evaluationId),
                (rs, rowNum) -> mapScore(rs));
        return list.stream().findFirst();
    }

    public List<ScoreMetric> listScoreMetrics(UUID evaluationScoreId) {
        return jdbc.query("""
                SELECT id, evaluation_score_id, metric_key, raw_value, weight, weighted_value, meta_json::text AS meta_json
                FROM evaluations.score_metrics
                WHERE evaluation_score_id = :scoreId
                ORDER BY metric_key
                """,
                new MapSqlParameterSource("scoreId", evaluationScoreId),
                (rs, rowNum) -> new ScoreMetric(
                        rs.getObject("id", UUID.class),
                        rs.getObject("evaluation_score_id", UUID.class),
                        rs.getString("metric_key"),
                        rs.getBigDecimal("raw_value"),
                        rs.getBigDecimal("weight"),
                        rs.getBigDecimal("weighted_value"),
                        rs.getString("meta_json")
                ));
    }

    public record OutboxRow(long id, UUID aggregateId, UUID orgId, String eventType, String payload, int publishAttempts) {
    }

    private static MapSqlParameterSource params(Evaluation evaluation) {
        return new MapSqlParameterSource()
                .addValue("id", evaluation.id())
                .addValue("orgId", evaluation.orgId())
                .addValue("benchmarkVersionId", evaluation.benchmarkVersionId())
                .addValue("taskId", evaluation.taskId())
                .addValue("agentId", evaluation.agentId())
                .addValue("state", evaluation.state().name())
                .addValue("attempt", evaluation.attempt())
                .addValue("failureClass", evaluation.failureClass() == null ? null : evaluation.failureClass().name())
                .addValue("errorCode", evaluation.errorCode())
                .addValue("errorMessage", evaluation.errorMessage())
                .addValue("idempotencyKey", evaluation.idempotencyKey())
                .addValue("snapshotJson", evaluation.snapshotJson())
                .addValue("workspaceUri", evaluation.workspaceUri())
                .addValue("cancelRequested", evaluation.cancelRequested())
                .addValue("createdBy", evaluation.createdBy())
                .addValue("createdAt", Timestamp.from(evaluation.createdAt()))
                .addValue("updatedAt", Timestamp.from(evaluation.updatedAt()))
                .addValue("startedAt", evaluation.startedAt() == null ? null : Timestamp.from(evaluation.startedAt()))
                .addValue("completedAt", evaluation.completedAt() == null ? null : Timestamp.from(evaluation.completedAt()));
    }

    private static Evaluation mapEvaluation(ResultSet rs) throws SQLException {
        String failureClass = rs.getString("failure_class");
        return new Evaluation(
                rs.getObject("id", UUID.class),
                rs.getObject("org_id", UUID.class),
                rs.getObject("benchmark_version_id", UUID.class),
                rs.getObject("task_id", UUID.class),
                rs.getObject("agent_id", UUID.class),
                EvaluationState.fromString(rs.getString("state")),
                rs.getInt("attempt"),
                failureClass == null ? null : FailureClass.fromString(failureClass),
                rs.getString("error_code"),
                rs.getString("error_message"),
                rs.getString("idempotency_key"),
                rs.getString("snapshot_json"),
                rs.getString("workspace_uri"),
                rs.getBoolean("cancel_requested"),
                rs.getObject("created_by", UUID.class),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant(),
                rs.getTimestamp("started_at") == null ? null : rs.getTimestamp("started_at").toInstant(),
                rs.getTimestamp("completed_at") == null ? null : rs.getTimestamp("completed_at").toInstant()
        );
    }

    private static TestRun mapTestRun(ResultSet rs) throws SQLException {
        return new TestRun(
                rs.getObject("id", UUID.class),
                rs.getObject("evaluation_id", UUID.class),
                rs.getObject("org_id", UUID.class),
                TestPhase.valueOf(rs.getString("phase")),
                rs.getObject("suite_id", UUID.class),
                rs.getString("status"),
                rs.getTimestamp("started_at").toInstant(),
                rs.getTimestamp("finished_at") == null ? null : rs.getTimestamp("finished_at").toInstant(),
                rs.getString("container_meta")
        );
    }

    private static TestCaseResult mapTestCaseResult(ResultSet rs) throws SQLException {
        String category = rs.getString("category");
        return new TestCaseResult(
                rs.getObject("id", UUID.class),
                rs.getObject("test_run_id", UUID.class),
                rs.getObject("org_id", UUID.class),
                rs.getString("case_key"),
                TestCaseStatus.valueOf(rs.getString("status")),
                rs.getInt("duration_ms"),
                rs.getString("transition"),
                rs.getString("message"),
                rs.getString("log_excerpt_uri"),
                category == null ? null : CaseCategory.valueOf(category),
                rs.getBoolean("flaky"),
                rs.getInt("attempt_count"),
                rs.getString("meta_json")
        );
    }

    private EvaluationScore mapScore(ResultSet rs) throws SQLException {
        BigDecimal weighted = rs.getBigDecimal("weighted_total");
        return new EvaluationScore(
                rs.getObject("id", UUID.class),
                rs.getObject("evaluation_id", UUID.class),
                rs.getObject("org_id", UUID.class),
                weighted,
                rs.getString("profile_id"),
                rs.getString("profile_key"),
                ScoreStatus.valueOf(rs.getString("score_status")),
                rs.getString("transition_summary_json"),
                rs.getString("raw_summary_json"),
                rs.getString("warnings_json"),
                rs.getTimestamp("computed_at").toInstant()
        );
    }

    private ScoringProfile mapScoringProfile(ResultSet rs) throws SQLException {
        Map<String, Double> weights = new HashMap<>(ScoringProfile.DEFAULT_WEIGHTS);
        try {
            Map<String, Object> raw = objectMapper.readValue(
                    rs.getString("weights_json"), new TypeReference<>() {});
            for (Map.Entry<String, Object> entry : raw.entrySet()) {
                if (entry.getValue() instanceof Number number) {
                    weights.put(entry.getKey(), number.doubleValue());
                }
            }
        } catch (Exception ignored) {
            // keep defaults
        }
        return new ScoringProfile(
                rs.getObject("id", UUID.class),
                rs.getString("profile_key"),
                rs.getString("display_name"),
                rs.getString("description"),
                Map.copyOf(weights),
                rs.getInt("flaky_repeat_runs"),
                rs.getBoolean("active"),
                rs.getTimestamp("created_at").toInstant()
        );
    }
}
