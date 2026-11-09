package com.agentbench.projects.infra;

import com.agentbench.projects.domain.Project;
import com.agentbench.projects.domain.ProjectRepositoryConfig;
import com.agentbench.projects.domain.RepoSnapshot;
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
public class ProjectRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public ProjectRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Project insert(UUID orgId, String name, String description, UUID createdBy) {
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        jdbc.update("""
                INSERT INTO projects.projects
                    (id, org_id, name, description, created_by, created_at, updated_at)
                VALUES
                    (:id, :orgId, :name, :description, :createdBy, :createdAt, :updatedAt)
                """,
                new MapSqlParameterSource()
                        .addValue("id", id)
                        .addValue("orgId", orgId)
                        .addValue("name", name)
                        .addValue("description", description)
                        .addValue("createdBy", createdBy)
                        .addValue("createdAt", Timestamp.from(now))
                        .addValue("updatedAt", Timestamp.from(now)));
        return new Project(id, orgId, name, description, createdBy, now, now);
    }

    public Optional<Project> findById(UUID orgId, UUID projectId) {
        var list = jdbc.query("""
                SELECT id, org_id, name, description, created_by, created_at, updated_at
                FROM projects.projects
                WHERE org_id = :orgId AND id = :id
                """,
                new MapSqlParameterSource()
                        .addValue("orgId", orgId)
                        .addValue("id", projectId),
                (rs, rowNum) -> mapProject(rs));
        return list.stream().findFirst();
    }

    public List<Project> listByOrg(UUID orgId) {
        return jdbc.query("""
                SELECT id, org_id, name, description, created_by, created_at, updated_at
                FROM projects.projects
                WHERE org_id = :orgId
                ORDER BY name
                """,
                new MapSqlParameterSource("orgId", orgId),
                (rs, rowNum) -> mapProject(rs));
    }

    public Project update(UUID orgId, UUID projectId, String name, String description) {
        jdbc.update("""
                UPDATE projects.projects
                SET name = :name, description = :description, updated_at = :updatedAt
                WHERE org_id = :orgId AND id = :id
                """,
                new MapSqlParameterSource()
                        .addValue("orgId", orgId)
                        .addValue("id", projectId)
                        .addValue("name", name)
                        .addValue("description", description)
                        .addValue("updatedAt", Timestamp.from(Instant.now())));
        return findById(orgId, projectId).orElseThrow();
    }

    public void delete(UUID orgId, UUID projectId) {
        jdbc.update("""
                DELETE FROM projects.projects
                WHERE org_id = :orgId AND id = :id
                """,
                new MapSqlParameterSource()
                        .addValue("orgId", orgId)
                        .addValue("id", projectId));
    }

    public ProjectRepositoryConfig upsertRepository(
            UUID orgId,
            UUID projectId,
            String gitUrl,
            String defaultBranch,
            String commitSha,
            String language,
            String buildCommand,
            String testCommand,
            UUID secretRefId) {
        Optional<ProjectRepositoryConfig> existing = findRepository(orgId, projectId);
        Instant now = Instant.now();
        if (existing.isPresent()) {
            jdbc.update("""
                    UPDATE projects.project_repositories
                    SET git_url = :gitUrl,
                        default_branch = :defaultBranch,
                        commit_sha = :commitSha,
                        language = :language,
                        build_command = :buildCommand,
                        test_command = :testCommand,
                        secret_ref_id = :secretRefId,
                        validation_status = 'UNKNOWN',
                        validation_message = NULL,
                        last_validated_at = NULL,
                        updated_at = :updatedAt
                    WHERE org_id = :orgId AND project_id = :projectId
                    """,
                    new MapSqlParameterSource()
                            .addValue("orgId", orgId)
                            .addValue("projectId", projectId)
                            .addValue("gitUrl", gitUrl)
                            .addValue("defaultBranch", defaultBranch)
                            .addValue("commitSha", commitSha)
                            .addValue("language", language)
                            .addValue("buildCommand", buildCommand)
                            .addValue("testCommand", testCommand)
                            .addValue("secretRefId", secretRefId)
                            .addValue("updatedAt", Timestamp.from(now)));
        } else {
            UUID id = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO projects.project_repositories
                        (id, org_id, project_id, git_url, default_branch, commit_sha, language,
                         build_command, test_command, secret_ref_id, validation_status, created_at, updated_at)
                    VALUES
                        (:id, :orgId, :projectId, :gitUrl, :defaultBranch, :commitSha, :language,
                         :buildCommand, :testCommand, :secretRefId, 'UNKNOWN', :createdAt, :updatedAt)
                    """,
                    new MapSqlParameterSource()
                            .addValue("id", id)
                            .addValue("orgId", orgId)
                            .addValue("projectId", projectId)
                            .addValue("gitUrl", gitUrl)
                            .addValue("defaultBranch", defaultBranch)
                            .addValue("commitSha", commitSha)
                            .addValue("language", language)
                            .addValue("buildCommand", buildCommand)
                            .addValue("testCommand", testCommand)
                            .addValue("secretRefId", secretRefId)
                            .addValue("createdAt", Timestamp.from(now))
                            .addValue("updatedAt", Timestamp.from(now)));
        }
        return findRepository(orgId, projectId).orElseThrow();
    }

    public Optional<ProjectRepositoryConfig> findRepository(UUID orgId, UUID projectId) {
        var list = jdbc.query("""
                SELECT id, org_id, project_id, git_url, default_branch, commit_sha, language,
                       build_command, test_command, secret_ref_id, validation_status, validation_message,
                       last_validated_at, created_at, updated_at
                FROM projects.project_repositories
                WHERE org_id = :orgId AND project_id = :projectId
                """,
                new MapSqlParameterSource()
                        .addValue("orgId", orgId)
                        .addValue("projectId", projectId),
                (rs, rowNum) -> mapRepo(rs));
        return list.stream().findFirst();
    }

    public void updateValidation(UUID orgId, UUID projectId, String status, String message) {
        jdbc.update("""
                UPDATE projects.project_repositories
                SET validation_status = :status,
                    validation_message = :message,
                    last_validated_at = :validatedAt,
                    updated_at = :updatedAt
                WHERE org_id = :orgId AND project_id = :projectId
                """,
                new MapSqlParameterSource()
                        .addValue("orgId", orgId)
                        .addValue("projectId", projectId)
                        .addValue("status", status)
                        .addValue("message", message)
                        .addValue("validatedAt", Timestamp.from(Instant.now()))
                        .addValue("updatedAt", Timestamp.from(Instant.now())));
    }

    public RepoSnapshot insertSnapshot(
            UUID orgId,
            UUID projectId,
            String gitUrl,
            String commitSha,
            String branch,
            String contentDigest,
            String storageUri) {
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        jdbc.update("""
                INSERT INTO projects.repo_snapshots
                    (id, org_id, project_id, git_url, commit_sha, branch, content_digest, storage_uri, created_at)
                VALUES
                    (:id, :orgId, :projectId, :gitUrl, :commitSha, :branch, :contentDigest, :storageUri, :createdAt)
                """,
                new MapSqlParameterSource()
                        .addValue("id", id)
                        .addValue("orgId", orgId)
                        .addValue("projectId", projectId)
                        .addValue("gitUrl", gitUrl)
                        .addValue("commitSha", commitSha)
                        .addValue("branch", branch)
                        .addValue("contentDigest", contentDigest == null ? "" : contentDigest)
                        .addValue("storageUri", storageUri == null ? "" : storageUri)
                        .addValue("createdAt", Timestamp.from(now)));
        return new RepoSnapshot(id, orgId, projectId, gitUrl, commitSha, branch,
                contentDigest == null ? "" : contentDigest,
                storageUri == null ? "" : storageUri,
                now);
    }

    public Optional<RepoSnapshot> findSnapshot(UUID orgId, UUID snapshotId) {
        var list = jdbc.query("""
                SELECT id, org_id, project_id, git_url, commit_sha, branch, content_digest, storage_uri, created_at
                FROM projects.repo_snapshots
                WHERE org_id = :orgId AND id = :id
                """,
                new MapSqlParameterSource()
                        .addValue("orgId", orgId)
                        .addValue("id", snapshotId),
                (rs, rowNum) -> mapSnapshot(rs));
        return list.stream().findFirst();
    }

    public List<RepoSnapshot> listSnapshots(UUID orgId, UUID projectId) {
        return jdbc.query("""
                SELECT id, org_id, project_id, git_url, commit_sha, branch, content_digest, storage_uri, created_at
                FROM projects.repo_snapshots
                WHERE org_id = :orgId AND project_id = :projectId
                ORDER BY created_at DESC
                """,
                new MapSqlParameterSource()
                        .addValue("orgId", orgId)
                        .addValue("projectId", projectId),
                (rs, rowNum) -> mapSnapshot(rs));
    }

    private static Project mapProject(ResultSet rs) throws SQLException {
        return new Project(
                rs.getObject("id", UUID.class),
                rs.getObject("org_id", UUID.class),
                rs.getString("name"),
                rs.getString("description"),
                rs.getObject("created_by", UUID.class),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant()
        );
    }

    private static ProjectRepositoryConfig mapRepo(ResultSet rs) throws SQLException {
        return new ProjectRepositoryConfig(
                rs.getObject("id", UUID.class),
                rs.getObject("org_id", UUID.class),
                rs.getObject("project_id", UUID.class),
                rs.getString("git_url"),
                rs.getString("default_branch"),
                rs.getString("commit_sha"),
                rs.getString("language"),
                rs.getString("build_command"),
                rs.getString("test_command"),
                rs.getObject("secret_ref_id", UUID.class),
                rs.getString("validation_status"),
                rs.getString("validation_message"),
                rs.getTimestamp("last_validated_at") == null ? null : rs.getTimestamp("last_validated_at").toInstant(),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant()
        );
    }

    private static RepoSnapshot mapSnapshot(ResultSet rs) throws SQLException {
        return new RepoSnapshot(
                rs.getObject("id", UUID.class),
                rs.getObject("org_id", UUID.class),
                rs.getObject("project_id", UUID.class),
                rs.getString("git_url"),
                rs.getString("commit_sha"),
                rs.getString("branch"),
                rs.getString("content_digest"),
                rs.getString("storage_uri"),
                rs.getTimestamp("created_at").toInstant()
        );
    }
}
