package com.agentbench.benchmarks.infra;

import com.agentbench.benchmarks.domain.Benchmark;
import com.agentbench.benchmarks.domain.BenchmarkStatus;
import com.agentbench.benchmarks.domain.BenchmarkVersion;
import com.agentbench.benchmarks.domain.SuiteVisibility;
import com.agentbench.benchmarks.domain.Task;
import com.agentbench.benchmarks.domain.TaskDifficulty;
import com.agentbench.benchmarks.domain.TestSuite;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class BenchmarkRepository {

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public BenchmarkRepository(NamedParameterJdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public Benchmark insertBenchmark(UUID orgId, UUID projectId, String name, String description, UUID createdBy) {
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        jdbc.update("""
                INSERT INTO benchmarks.benchmarks
                    (id, org_id, project_id, name, description, created_by, created_at, updated_at)
                VALUES
                    (:id, :orgId, :projectId, :name, :description, :createdBy, :createdAt, :updatedAt)
                """,
                new MapSqlParameterSource()
                        .addValue("id", id)
                        .addValue("orgId", orgId)
                        .addValue("projectId", projectId)
                        .addValue("name", name)
                        .addValue("description", description)
                        .addValue("createdBy", createdBy)
                        .addValue("createdAt", Timestamp.from(now))
                        .addValue("updatedAt", Timestamp.from(now)));
        return new Benchmark(id, orgId, projectId, name, description, createdBy, now, now);
    }

    public Optional<Benchmark> findBenchmark(UUID orgId, UUID benchmarkId) {
        var list = jdbc.query("""
                SELECT id, org_id, project_id, name, description, created_by, created_at, updated_at
                FROM benchmarks.benchmarks
                WHERE org_id = :orgId AND id = :id
                """,
                new MapSqlParameterSource()
                        .addValue("orgId", orgId)
                        .addValue("id", benchmarkId),
                (rs, rowNum) -> mapBenchmark(rs));
        return list.stream().findFirst();
    }

    public List<Benchmark> listBenchmarks(UUID orgId) {
        return jdbc.query("""
                SELECT id, org_id, project_id, name, description, created_by, created_at, updated_at
                FROM benchmarks.benchmarks
                WHERE org_id = :orgId
                ORDER BY name
                """,
                new MapSqlParameterSource("orgId", orgId),
                (rs, rowNum) -> mapBenchmark(rs));
    }

    public Benchmark updateBenchmark(UUID orgId, UUID benchmarkId, String name, String description, UUID projectId) {
        jdbc.update("""
                UPDATE benchmarks.benchmarks
                SET name = :name, description = :description, project_id = :projectId, updated_at = :updatedAt
                WHERE org_id = :orgId AND id = :id
                """,
                new MapSqlParameterSource()
                        .addValue("orgId", orgId)
                        .addValue("id", benchmarkId)
                        .addValue("name", name)
                        .addValue("description", description)
                        .addValue("projectId", projectId)
                        .addValue("updatedAt", Timestamp.from(Instant.now())));
        return findBenchmark(orgId, benchmarkId).orElseThrow();
    }

    public void deleteBenchmark(UUID orgId, UUID benchmarkId) {
        jdbc.update("""
                DELETE FROM benchmarks.benchmarks
                WHERE org_id = :orgId AND id = :id
                """,
                new MapSqlParameterSource()
                        .addValue("orgId", orgId)
                        .addValue("id", benchmarkId));
    }

    public boolean hasPublishedVersions(UUID orgId, UUID benchmarkId) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM benchmarks.benchmark_versions
                WHERE org_id = :orgId AND benchmark_id = :benchmarkId AND status IN ('PUBLISHED', 'RETIRED')
                """,
                new MapSqlParameterSource()
                        .addValue("orgId", orgId)
                        .addValue("benchmarkId", benchmarkId),
                Integer.class);
        return count != null && count > 0;
    }

    public BenchmarkVersion insertVersion(
            UUID orgId,
            UUID benchmarkId,
            String version,
            String changelog,
            String scoringProfileKey) {
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        jdbc.update("""
                INSERT INTO benchmarks.benchmark_versions
                    (id, benchmark_id, org_id, version, status, changelog, scoring_profile_key,
                     created_at, updated_at)
                VALUES
                    (:id, :benchmarkId, :orgId, :version, 'DRAFT', :changelog, :scoringProfileKey,
                     :createdAt, :updatedAt)
                """,
                new MapSqlParameterSource()
                        .addValue("id", id)
                        .addValue("benchmarkId", benchmarkId)
                        .addValue("orgId", orgId)
                        .addValue("version", version)
                        .addValue("changelog", changelog)
                        .addValue("scoringProfileKey", scoringProfileKey)
                        .addValue("createdAt", Timestamp.from(now))
                        .addValue("updatedAt", Timestamp.from(now)));
        return findVersion(orgId, id).orElseThrow();
    }

    public Optional<BenchmarkVersion> findVersion(UUID orgId, UUID versionId) {
        var list = jdbc.query("""
                SELECT id, benchmark_id, org_id, version, status, changelog, scoring_profile_key,
                       published_at, published_by, immutable_snapshot::text AS immutable_snapshot,
                       created_at, updated_at
                FROM benchmarks.benchmark_versions
                WHERE org_id = :orgId AND id = :id
                """,
                new MapSqlParameterSource()
                        .addValue("orgId", orgId)
                        .addValue("id", versionId),
                (rs, rowNum) -> mapVersion(rs));
        return list.stream().findFirst();
    }

    public List<BenchmarkVersion> listVersions(UUID orgId, UUID benchmarkId) {
        return jdbc.query("""
                SELECT id, benchmark_id, org_id, version, status, changelog, scoring_profile_key,
                       published_at, published_by, immutable_snapshot::text AS immutable_snapshot,
                       created_at, updated_at
                FROM benchmarks.benchmark_versions
                WHERE org_id = :orgId AND benchmark_id = :benchmarkId
                ORDER BY created_at DESC
                """,
                new MapSqlParameterSource()
                        .addValue("orgId", orgId)
                        .addValue("benchmarkId", benchmarkId),
                (rs, rowNum) -> mapVersion(rs));
    }

    public BenchmarkVersion publishVersion(UUID orgId, UUID versionId, UUID publishedBy, String snapshotJson) {
        Instant now = Instant.now();
        int updated = jdbc.update("""
                UPDATE benchmarks.benchmark_versions
                SET status = 'PUBLISHED',
                    published_at = :publishedAt,
                    published_by = :publishedBy,
                    immutable_snapshot = CAST(:snapshot AS jsonb),
                    updated_at = :updatedAt
                WHERE org_id = :orgId AND id = :id AND status = 'DRAFT'
                """,
                new MapSqlParameterSource()
                        .addValue("orgId", orgId)
                        .addValue("id", versionId)
                        .addValue("publishedAt", Timestamp.from(now))
                        .addValue("publishedBy", publishedBy)
                        .addValue("snapshot", snapshotJson)
                        .addValue("updatedAt", Timestamp.from(now)));
        if (updated == 0) {
            return findVersion(orgId, versionId).orElseThrow();
        }
        return findVersion(orgId, versionId).orElseThrow();
    }

    public Task insertTask(
            UUID orgId,
            UUID versionId,
            String slug,
            String title,
            String prompt,
            String acceptanceCriteria,
            String category,
            TaskDifficulty difficulty,
            String languageTagsJson,
            UUID repoSnapshotId,
            String baselineCommitSha,
            String referencePatch,
            String expectedBehavior,
            int timeoutSec,
            int memoryMb,
            BigDecimal cpuLimit,
            int maxPatchBytes,
            String evalConfigJson,
            int ordinal) {
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        jdbc.update("""
                INSERT INTO benchmarks.tasks
                    (id, benchmark_version_id, org_id, slug, title, prompt, acceptance_criteria,
                     category, difficulty, language_tags, repo_snapshot_id, baseline_commit_sha,
                     reference_patch, expected_behavior, timeout_sec, memory_mb, cpu_limit,
                     max_patch_bytes, eval_config_json, ordinal, created_at, updated_at)
                VALUES
                    (:id, :versionId, :orgId, :slug, :title, :prompt, :acceptanceCriteria,
                     :category, :difficulty, CAST(:languageTags AS jsonb), :repoSnapshotId, :baselineCommitSha,
                     :referencePatch, :expectedBehavior, :timeoutSec, :memoryMb, :cpuLimit,
                     :maxPatchBytes, CAST(:evalConfig AS jsonb), :ordinal, :createdAt, :updatedAt)
                """,
                new MapSqlParameterSource()
                        .addValue("id", id)
                        .addValue("versionId", versionId)
                        .addValue("orgId", orgId)
                        .addValue("slug", slug)
                        .addValue("title", title)
                        .addValue("prompt", prompt)
                        .addValue("acceptanceCriteria", acceptanceCriteria)
                        .addValue("category", category)
                        .addValue("difficulty", difficulty == null ? null : difficulty.name())
                        .addValue("languageTags", languageTagsJson)
                        .addValue("repoSnapshotId", repoSnapshotId)
                        .addValue("baselineCommitSha", baselineCommitSha)
                        .addValue("referencePatch", referencePatch)
                        .addValue("expectedBehavior", expectedBehavior)
                        .addValue("timeoutSec", timeoutSec)
                        .addValue("memoryMb", memoryMb)
                        .addValue("cpuLimit", cpuLimit)
                        .addValue("maxPatchBytes", maxPatchBytes)
                        .addValue("evalConfig", evalConfigJson)
                        .addValue("ordinal", ordinal)
                        .addValue("createdAt", Timestamp.from(now))
                        .addValue("updatedAt", Timestamp.from(now)));
        return findTask(orgId, id).orElseThrow();
    }

    public Optional<Task> findTask(UUID orgId, UUID taskId) {
        var list = jdbc.query("""
                SELECT id, benchmark_version_id, org_id, slug, title, prompt, acceptance_criteria,
                       category, difficulty, language_tags::text AS language_tags,
                       repo_snapshot_id, baseline_commit_sha, reference_patch, expected_behavior,
                       timeout_sec, memory_mb, cpu_limit, max_patch_bytes,
                       eval_config_json::text AS eval_config_json, ordinal, created_at, updated_at
                FROM benchmarks.tasks
                WHERE org_id = :orgId AND id = :id
                """,
                new MapSqlParameterSource()
                        .addValue("orgId", orgId)
                        .addValue("id", taskId),
                (rs, rowNum) -> mapTask(rs));
        return list.stream().findFirst();
    }

    public List<Task> listTasks(UUID orgId, UUID versionId) {
        return jdbc.query("""
                SELECT id, benchmark_version_id, org_id, slug, title, prompt, acceptance_criteria,
                       category, difficulty, language_tags::text AS language_tags,
                       repo_snapshot_id, baseline_commit_sha, reference_patch, expected_behavior,
                       timeout_sec, memory_mb, cpu_limit, max_patch_bytes,
                       eval_config_json::text AS eval_config_json, ordinal, created_at, updated_at
                FROM benchmarks.tasks
                WHERE org_id = :orgId AND benchmark_version_id = :versionId
                ORDER BY ordinal, slug
                """,
                new MapSqlParameterSource()
                        .addValue("orgId", orgId)
                        .addValue("versionId", versionId),
                (rs, rowNum) -> mapTask(rs));
    }

    public Task updateTask(
            UUID orgId,
            UUID taskId,
            String title,
            String prompt,
            String acceptanceCriteria,
            String category,
            TaskDifficulty difficulty,
            String languageTagsJson,
            UUID repoSnapshotId,
            String baselineCommitSha,
            String referencePatch,
            String expectedBehavior,
            int timeoutSec,
            int memoryMb,
            BigDecimal cpuLimit,
            int maxPatchBytes,
            String evalConfigJson,
            int ordinal) {
        jdbc.update("""
                UPDATE benchmarks.tasks
                SET title = :title,
                    prompt = :prompt,
                    acceptance_criteria = :acceptanceCriteria,
                    category = :category,
                    difficulty = :difficulty,
                    language_tags = CAST(:languageTags AS jsonb),
                    repo_snapshot_id = :repoSnapshotId,
                    baseline_commit_sha = :baselineCommitSha,
                    reference_patch = :referencePatch,
                    expected_behavior = :expectedBehavior,
                    timeout_sec = :timeoutSec,
                    memory_mb = :memoryMb,
                    cpu_limit = :cpuLimit,
                    max_patch_bytes = :maxPatchBytes,
                    eval_config_json = CAST(:evalConfig AS jsonb),
                    ordinal = :ordinal,
                    updated_at = :updatedAt
                WHERE org_id = :orgId AND id = :id
                """,
                new MapSqlParameterSource()
                        .addValue("orgId", orgId)
                        .addValue("id", taskId)
                        .addValue("title", title)
                        .addValue("prompt", prompt)
                        .addValue("acceptanceCriteria", acceptanceCriteria)
                        .addValue("category", category)
                        .addValue("difficulty", difficulty == null ? null : difficulty.name())
                        .addValue("languageTags", languageTagsJson)
                        .addValue("repoSnapshotId", repoSnapshotId)
                        .addValue("baselineCommitSha", baselineCommitSha)
                        .addValue("referencePatch", referencePatch)
                        .addValue("expectedBehavior", expectedBehavior)
                        .addValue("timeoutSec", timeoutSec)
                        .addValue("memoryMb", memoryMb)
                        .addValue("cpuLimit", cpuLimit)
                        .addValue("maxPatchBytes", maxPatchBytes)
                        .addValue("evalConfig", evalConfigJson)
                        .addValue("ordinal", ordinal)
                        .addValue("updatedAt", Timestamp.from(Instant.now())));
        return findTask(orgId, taskId).orElseThrow();
    }

    public void deleteTask(UUID orgId, UUID taskId) {
        jdbc.update("""
                DELETE FROM benchmarks.tasks
                WHERE org_id = :orgId AND id = :id
                """,
                new MapSqlParameterSource()
                        .addValue("orgId", orgId)
                        .addValue("id", taskId));
    }

    public TestSuite upsertSuite(
            UUID orgId,
            UUID taskId,
            SuiteVisibility visibility,
            String framework,
            String storageUri,
            String entryCommand,
            String contentDigest,
            String fixtureContent,
            String expectedOutcomesJson,
            Integer caseCount) {
        Optional<TestSuite> existing = findSuite(orgId, taskId, visibility);
        Instant now = Instant.now();
        if (existing.isPresent()) {
            jdbc.update("""
                    UPDATE benchmarks.test_suites
                    SET framework = :framework,
                        storage_uri = :storageUri,
                        entry_command = :entryCommand,
                        content_digest = :contentDigest,
                        fixture_content = :fixtureContent,
                        expected_outcomes_json = CAST(:expectedOutcomes AS jsonb),
                        case_count = :caseCount,
                        updated_at = :updatedAt
                    WHERE org_id = :orgId AND task_id = :taskId AND visibility = :visibility
                    """,
                    new MapSqlParameterSource()
                            .addValue("orgId", orgId)
                            .addValue("taskId", taskId)
                            .addValue("visibility", visibility.name())
                            .addValue("framework", framework)
                            .addValue("storageUri", storageUri)
                            .addValue("entryCommand", entryCommand)
                            .addValue("contentDigest", contentDigest)
                            .addValue("fixtureContent", fixtureContent)
                            .addValue("expectedOutcomes", expectedOutcomesJson)
                            .addValue("caseCount", caseCount)
                            .addValue("updatedAt", Timestamp.from(now)));
        } else {
            UUID id = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO benchmarks.test_suites
                        (id, task_id, org_id, visibility, framework, storage_uri, entry_command,
                         content_digest, fixture_content, expected_outcomes_json, case_count,
                         created_at, updated_at)
                    VALUES
                        (:id, :taskId, :orgId, :visibility, :framework, :storageUri, :entryCommand,
                         :contentDigest, :fixtureContent, CAST(:expectedOutcomes AS jsonb), :caseCount,
                         :createdAt, :updatedAt)
                    """,
                    new MapSqlParameterSource()
                            .addValue("id", id)
                            .addValue("taskId", taskId)
                            .addValue("orgId", orgId)
                            .addValue("visibility", visibility.name())
                            .addValue("framework", framework)
                            .addValue("storageUri", storageUri)
                            .addValue("entryCommand", entryCommand)
                            .addValue("contentDigest", contentDigest)
                            .addValue("fixtureContent", fixtureContent)
                            .addValue("expectedOutcomes", expectedOutcomesJson)
                            .addValue("caseCount", caseCount)
                            .addValue("createdAt", Timestamp.from(now))
                            .addValue("updatedAt", Timestamp.from(now)));
        }
        return findSuite(orgId, taskId, visibility).orElseThrow();
    }

    public Optional<TestSuite> findSuite(UUID orgId, UUID taskId, SuiteVisibility visibility) {
        var list = jdbc.query("""
                SELECT id, task_id, org_id, visibility, framework, storage_uri, entry_command,
                       content_digest, fixture_content,
                       expected_outcomes_json::text AS expected_outcomes_json,
                       case_count, created_at, updated_at
                FROM benchmarks.test_suites
                WHERE org_id = :orgId AND task_id = :taskId AND visibility = :visibility
                """,
                new MapSqlParameterSource()
                        .addValue("orgId", orgId)
                        .addValue("taskId", taskId)
                        .addValue("visibility", visibility.name()),
                (rs, rowNum) -> mapSuite(rs));
        return list.stream().findFirst();
    }

    public List<TestSuite> listSuitesForTask(UUID orgId, UUID taskId) {
        return jdbc.query("""
                SELECT id, task_id, org_id, visibility, framework, storage_uri, entry_command,
                       content_digest, fixture_content,
                       expected_outcomes_json::text AS expected_outcomes_json,
                       case_count, created_at, updated_at
                FROM benchmarks.test_suites
                WHERE org_id = :orgId AND task_id = :taskId
                ORDER BY visibility
                """,
                new MapSqlParameterSource()
                        .addValue("orgId", orgId)
                        .addValue("taskId", taskId),
                (rs, rowNum) -> mapSuite(rs));
    }

    public List<TestSuite> listSuitesForVersion(UUID orgId, UUID versionId) {
        return jdbc.query("""
                SELECT s.id, s.task_id, s.org_id, s.visibility, s.framework, s.storage_uri, s.entry_command,
                       s.content_digest, s.fixture_content,
                       s.expected_outcomes_json::text AS expected_outcomes_json,
                       s.case_count, s.created_at, s.updated_at
                FROM benchmarks.test_suites s
                JOIN benchmarks.tasks t ON t.id = s.task_id
                WHERE s.org_id = :orgId AND t.benchmark_version_id = :versionId
                ORDER BY t.ordinal, s.visibility
                """,
                new MapSqlParameterSource()
                        .addValue("orgId", orgId)
                        .addValue("versionId", versionId),
                (rs, rowNum) -> mapSuite(rs));
    }

    private Benchmark mapBenchmark(ResultSet rs) throws SQLException {
        return new Benchmark(
                (UUID) rs.getObject("id"),
                (UUID) rs.getObject("org_id"),
                (UUID) rs.getObject("project_id"),
                rs.getString("name"),
                rs.getString("description"),
                (UUID) rs.getObject("created_by"),
                toInstant(rs.getTimestamp("created_at")),
                toInstant(rs.getTimestamp("updated_at"))
        );
    }

    private BenchmarkVersion mapVersion(ResultSet rs) throws SQLException {
        return new BenchmarkVersion(
                (UUID) rs.getObject("id"),
                (UUID) rs.getObject("benchmark_id"),
                (UUID) rs.getObject("org_id"),
                rs.getString("version"),
                BenchmarkStatus.fromString(rs.getString("status")),
                rs.getString("changelog"),
                rs.getString("scoring_profile_key"),
                toInstant(rs.getTimestamp("published_at")),
                (UUID) rs.getObject("published_by"),
                rs.getString("immutable_snapshot"),
                toInstant(rs.getTimestamp("created_at")),
                toInstant(rs.getTimestamp("updated_at"))
        );
    }

    private Task mapTask(ResultSet rs) throws SQLException {
        return new Task(
                (UUID) rs.getObject("id"),
                (UUID) rs.getObject("benchmark_version_id"),
                (UUID) rs.getObject("org_id"),
                rs.getString("slug"),
                rs.getString("title"),
                rs.getString("prompt"),
                rs.getString("acceptance_criteria"),
                rs.getString("category"),
                TaskDifficulty.fromString(rs.getString("difficulty")),
                readStringList(rs.getString("language_tags")),
                (UUID) rs.getObject("repo_snapshot_id"),
                trimToNull(rs.getString("baseline_commit_sha")),
                rs.getString("reference_patch"),
                rs.getString("expected_behavior"),
                rs.getInt("timeout_sec"),
                rs.getInt("memory_mb"),
                rs.getBigDecimal("cpu_limit"),
                rs.getInt("max_patch_bytes"),
                rs.getString("eval_config_json"),
                rs.getInt("ordinal"),
                toInstant(rs.getTimestamp("created_at")),
                toInstant(rs.getTimestamp("updated_at"))
        );
    }

    private TestSuite mapSuite(ResultSet rs) throws SQLException {
        return new TestSuite(
                (UUID) rs.getObject("id"),
                (UUID) rs.getObject("task_id"),
                (UUID) rs.getObject("org_id"),
                SuiteVisibility.fromString(rs.getString("visibility")),
                rs.getString("framework"),
                rs.getString("storage_uri"),
                rs.getString("entry_command"),
                rs.getString("content_digest"),
                rs.getString("fixture_content"),
                rs.getString("expected_outcomes_json"),
                (Integer) rs.getObject("case_count"),
                toInstant(rs.getTimestamp("created_at")),
                toInstant(rs.getTimestamp("updated_at"))
        );
    }

    private List<String> readStringList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<>() {
            });
        } catch (Exception ex) {
            return Collections.emptyList();
        }
    }

    private static Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
