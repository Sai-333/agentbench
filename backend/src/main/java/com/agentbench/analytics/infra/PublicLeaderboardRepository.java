package com.agentbench.analytics.infra;

import com.agentbench.analytics.domain.LeaderboardPublication;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Repository
public class PublicLeaderboardRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public PublicLeaderboardRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<LeaderboardPublication> findByOrgAndVersion(UUID orgId, UUID benchmarkVersionId) {
        try {
            return Optional.ofNullable(jdbc.queryForObject("""
                            SELECT id, org_id, benchmark_version_id, public_slug, title,
                                   published_at, published_by, unpublished_at, unpublished_by,
                                   created_at, updated_at
                            FROM analytics.leaderboard_publications
                            WHERE org_id = :orgId AND benchmark_version_id = :versionId
                            """,
                    new MapSqlParameterSource()
                            .addValue("orgId", orgId)
                            .addValue("versionId", benchmarkVersionId),
                    (rs, rowNum) -> map(rs)));
        } catch (EmptyResultDataAccessException ex) {
            return Optional.empty();
        }
    }

    public Optional<LeaderboardPublication> findActiveBySlug(String publicSlug) {
        try {
            return Optional.ofNullable(jdbc.queryForObject("""
                            SELECT id, org_id, benchmark_version_id, public_slug, title,
                                   published_at, published_by, unpublished_at, unpublished_by,
                                   created_at, updated_at
                            FROM analytics.leaderboard_publications
                            WHERE public_slug = :slug AND unpublished_at IS NULL
                            """,
                    new MapSqlParameterSource("slug", publicSlug),
                    (rs, rowNum) -> map(rs)));
        } catch (EmptyResultDataAccessException ex) {
            return Optional.empty();
        }
    }

    public boolean slugTaken(String publicSlug, UUID excludeOrgId, UUID excludeVersionId) {
        Long count = jdbc.queryForObject("""
                        SELECT COUNT(*) FROM analytics.leaderboard_publications
                        WHERE public_slug = :slug
                          AND NOT (org_id = :orgId AND benchmark_version_id = :versionId)
                        """,
                new MapSqlParameterSource()
                        .addValue("slug", publicSlug)
                        .addValue("orgId", excludeOrgId)
                        .addValue("versionId", excludeVersionId),
                Long.class);
        return count != null && count > 0;
    }

    public LeaderboardPublication upsertPublish(
            UUID orgId,
            UUID benchmarkVersionId,
            String publicSlug,
            String title,
            UUID publishedBy) {
        Instant now = Instant.now();
        UUID id = UUID.randomUUID();
        jdbc.update("""
                        INSERT INTO analytics.leaderboard_publications
                            (id, org_id, benchmark_version_id, public_slug, title,
                             published_at, published_by, unpublished_at, unpublished_by,
                             created_at, updated_at)
                        VALUES
                            (:id, :orgId, :versionId, :slug, :title,
                             :now, :publishedBy, NULL, NULL, :now, :now)
                        ON CONFLICT (org_id, benchmark_version_id) DO UPDATE SET
                            public_slug = CASE
                                WHEN analytics.leaderboard_publications.unpublished_at IS NULL
                                    THEN analytics.leaderboard_publications.public_slug
                                ELSE EXCLUDED.public_slug
                            END,
                            title = EXCLUDED.title,
                            published_at = EXCLUDED.published_at,
                            published_by = EXCLUDED.published_by,
                            unpublished_at = NULL,
                            unpublished_by = NULL,
                            updated_at = EXCLUDED.updated_at
                        """,
                new MapSqlParameterSource()
                        .addValue("id", id)
                        .addValue("orgId", orgId)
                        .addValue("versionId", benchmarkVersionId)
                        .addValue("slug", publicSlug)
                        .addValue("title", title)
                        .addValue("now", Timestamp.from(now))
                        .addValue("publishedBy", publishedBy));
        return findByOrgAndVersion(orgId, benchmarkVersionId)
                .orElseThrow(() -> new IllegalStateException("Publication row missing after upsert"));
    }

    public Optional<LeaderboardPublication> unpublish(UUID orgId, UUID benchmarkVersionId, UUID unpublishedBy) {
        Instant now = Instant.now();
        int updated = jdbc.update("""
                        UPDATE analytics.leaderboard_publications
                        SET unpublished_at = :now,
                            unpublished_by = :unpublishedBy,
                            updated_at = :now
                        WHERE org_id = :orgId
                          AND benchmark_version_id = :versionId
                          AND unpublished_at IS NULL
                        """,
                new MapSqlParameterSource()
                        .addValue("orgId", orgId)
                        .addValue("versionId", benchmarkVersionId)
                        .addValue("unpublishedBy", unpublishedBy)
                        .addValue("now", Timestamp.from(now)));
        if (updated == 0) {
            return findByOrgAndVersion(orgId, benchmarkVersionId);
        }
        return findByOrgAndVersion(orgId, benchmarkVersionId);
    }

    public Optional<VersionMeta> findVersionMeta(UUID orgId, UUID benchmarkVersionId) {
        try {
            return Optional.ofNullable(jdbc.queryForObject("""
                            SELECT bv.id AS version_id,
                                   bv.version AS version_label,
                                   bv.scoring_profile_key,
                                   b.name AS benchmark_name,
                                   o.slug AS org_slug,
                                   o.name AS org_name
                            FROM benchmarks.benchmark_versions bv
                            JOIN benchmarks.benchmarks b ON b.id = bv.benchmark_id
                            JOIN identity.organizations o ON o.id = bv.org_id
                            WHERE bv.org_id = :orgId AND bv.id = :versionId
                            """,
                    new MapSqlParameterSource()
                            .addValue("orgId", orgId)
                            .addValue("versionId", benchmarkVersionId),
                    (rs, rowNum) -> new VersionMeta(
                            rs.getObject("version_id", UUID.class),
                            rs.getString("version_label"),
                            rs.getString("scoring_profile_key"),
                            rs.getString("benchmark_name"),
                            rs.getString("org_slug"),
                            rs.getString("org_name")
                    )));
        } catch (EmptyResultDataAccessException ex) {
            return Optional.empty();
        }
    }

    public record VersionMeta(
            UUID versionId,
            String versionLabel,
            String scoringProfileKey,
            String benchmarkName,
            String orgSlug,
            String orgName
    ) {
    }

    private static LeaderboardPublication map(ResultSet rs) throws SQLException {
        return new LeaderboardPublication(
                rs.getObject("id", UUID.class),
                rs.getObject("org_id", UUID.class),
                rs.getObject("benchmark_version_id", UUID.class),
                rs.getString("public_slug"),
                rs.getString("title"),
                toInstant(rs.getTimestamp("published_at")),
                rs.getObject("published_by", UUID.class),
                toInstant(rs.getTimestamp("unpublished_at")),
                rs.getObject("unpublished_by", UUID.class),
                toInstant(rs.getTimestamp("created_at")),
                toInstant(rs.getTimestamp("updated_at"))
        );
    }

    private static Instant toInstant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }
}
