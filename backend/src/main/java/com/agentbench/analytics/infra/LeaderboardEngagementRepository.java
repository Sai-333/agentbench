package com.agentbench.analytics.infra;

import com.agentbench.analytics.domain.LeaderboardComment;
import com.agentbench.analytics.domain.LeaderboardVote;
import org.springframework.dao.EmptyResultDataAccessException;
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
public class LeaderboardEngagementRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public LeaderboardEngagementRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public LeaderboardComment insertComment(
            UUID orgId,
            UUID publicationId,
            String body,
            String displayName,
            UUID authorUserId) {
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        jdbc.update("""
                        INSERT INTO analytics.leaderboard_comments
                            (id, org_id, publication_id, body, display_name, author_user_id, created_at)
                        VALUES
                            (:id, :orgId, :publicationId, :body, :displayName, :authorUserId, :now)
                        """,
                new MapSqlParameterSource()
                        .addValue("id", id)
                        .addValue("orgId", orgId)
                        .addValue("publicationId", publicationId)
                        .addValue("body", body)
                        .addValue("displayName", displayName)
                        .addValue("authorUserId", authorUserId)
                        .addValue("now", Timestamp.from(now)));
        return findCommentById(orgId, id)
                .orElseThrow(() -> new IllegalStateException("Comment missing after insert"));
    }

    public Optional<LeaderboardComment> findCommentById(UUID orgId, UUID commentId) {
        try {
            return Optional.ofNullable(jdbc.queryForObject("""
                            SELECT id, org_id, publication_id, body, display_name, author_user_id,
                                   created_at, deleted_at, deleted_by
                            FROM analytics.leaderboard_comments
                            WHERE org_id = :orgId AND id = :id
                            """,
                    new MapSqlParameterSource()
                            .addValue("orgId", orgId)
                            .addValue("id", commentId),
                    (rs, rowNum) -> mapComment(rs)));
        } catch (EmptyResultDataAccessException ex) {
            return Optional.empty();
        }
    }

    public long countActiveComments(UUID orgId, UUID publicationId) {
        Long count = jdbc.queryForObject("""
                        SELECT COUNT(*) FROM analytics.leaderboard_comments
                        WHERE org_id = :orgId
                          AND publication_id = :publicationId
                          AND deleted_at IS NULL
                        """,
                new MapSqlParameterSource()
                        .addValue("orgId", orgId)
                        .addValue("publicationId", publicationId),
                Long.class);
        return count == null ? 0L : count;
    }

    public List<LeaderboardComment> listActiveComments(
            UUID orgId,
            UUID publicationId,
            int limit,
            int offset) {
        return jdbc.query("""
                        SELECT id, org_id, publication_id, body, display_name, author_user_id,
                               created_at, deleted_at, deleted_by
                        FROM analytics.leaderboard_comments
                        WHERE org_id = :orgId
                          AND publication_id = :publicationId
                          AND deleted_at IS NULL
                        ORDER BY created_at DESC
                        LIMIT :limit OFFSET :offset
                        """,
                new MapSqlParameterSource()
                        .addValue("orgId", orgId)
                        .addValue("publicationId", publicationId)
                        .addValue("limit", limit)
                        .addValue("offset", offset),
                (rs, rowNum) -> mapComment(rs));
    }

    public Optional<LeaderboardComment> softDeleteComment(
            UUID orgId,
            UUID commentId,
            UUID deletedBy) {
        Instant now = Instant.now();
        int updated = jdbc.update("""
                        UPDATE analytics.leaderboard_comments
                        SET deleted_at = :now,
                            deleted_by = :deletedBy
                        WHERE org_id = :orgId
                          AND id = :id
                          AND deleted_at IS NULL
                        """,
                new MapSqlParameterSource()
                        .addValue("orgId", orgId)
                        .addValue("id", commentId)
                        .addValue("deletedBy", deletedBy)
                        .addValue("now", Timestamp.from(now)));
        if (updated == 0) {
            return findCommentById(orgId, commentId);
        }
        return findCommentById(orgId, commentId);
    }

    public LeaderboardVote upsertVote(
            UUID orgId,
            UUID publicationId,
            String voterKey,
            int value,
            UUID authorUserId) {
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        jdbc.update("""
                        INSERT INTO analytics.leaderboard_votes
                            (id, org_id, publication_id, voter_key, value, author_user_id, created_at, updated_at)
                        VALUES
                            (:id, :orgId, :publicationId, :voterKey, :value, :authorUserId, :now, :now)
                        ON CONFLICT (publication_id, voter_key) DO UPDATE SET
                            value = EXCLUDED.value,
                            author_user_id = COALESCE(EXCLUDED.author_user_id,
                                analytics.leaderboard_votes.author_user_id),
                            updated_at = EXCLUDED.updated_at
                        """,
                new MapSqlParameterSource()
                        .addValue("id", id)
                        .addValue("orgId", orgId)
                        .addValue("publicationId", publicationId)
                        .addValue("voterKey", voterKey)
                        .addValue("value", value)
                        .addValue("authorUserId", authorUserId)
                        .addValue("now", Timestamp.from(now)));
        return findVote(orgId, publicationId, voterKey)
                .orElseThrow(() -> new IllegalStateException("Vote missing after upsert"));
    }

    public Optional<LeaderboardVote> findVote(UUID orgId, UUID publicationId, String voterKey) {
        try {
            return Optional.ofNullable(jdbc.queryForObject("""
                            SELECT id, org_id, publication_id, voter_key, value, author_user_id,
                                   created_at, updated_at
                            FROM analytics.leaderboard_votes
                            WHERE org_id = :orgId
                              AND publication_id = :publicationId
                              AND voter_key = :voterKey
                            """,
                    new MapSqlParameterSource()
                            .addValue("orgId", orgId)
                            .addValue("publicationId", publicationId)
                            .addValue("voterKey", voterKey),
                    (rs, rowNum) -> mapVote(rs)));
        } catch (EmptyResultDataAccessException ex) {
            return Optional.empty();
        }
    }

    public VoteTallies tallies(UUID orgId, UUID publicationId) {
        return jdbc.queryForObject("""
                        SELECT
                            COALESCE(SUM(CASE WHEN value = 1 THEN 1 ELSE 0 END), 0) AS up_count,
                            COALESCE(SUM(CASE WHEN value = -1 THEN 1 ELSE 0 END), 0) AS down_count
                        FROM analytics.leaderboard_votes
                        WHERE org_id = :orgId AND publication_id = :publicationId
                        """,
                new MapSqlParameterSource()
                        .addValue("orgId", orgId)
                        .addValue("publicationId", publicationId),
                (rs, rowNum) -> new VoteTallies(rs.getLong("up_count"), rs.getLong("down_count")));
    }

    public record VoteTallies(long upCount, long downCount) {
        public long score() {
            return upCount - downCount;
        }
    }

    private static LeaderboardComment mapComment(ResultSet rs) throws SQLException {
        return new LeaderboardComment(
                rs.getObject("id", UUID.class),
                rs.getObject("org_id", UUID.class),
                rs.getObject("publication_id", UUID.class),
                rs.getString("body"),
                rs.getString("display_name"),
                rs.getObject("author_user_id", UUID.class),
                toInstant(rs.getTimestamp("created_at")),
                toInstant(rs.getTimestamp("deleted_at")),
                rs.getObject("deleted_by", UUID.class)
        );
    }

    private static LeaderboardVote mapVote(ResultSet rs) throws SQLException {
        return new LeaderboardVote(
                rs.getObject("id", UUID.class),
                rs.getObject("org_id", UUID.class),
                rs.getObject("publication_id", UUID.class),
                rs.getString("voter_key"),
                rs.getInt("value"),
                rs.getObject("author_user_id", UUID.class),
                toInstant(rs.getTimestamp("created_at")),
                toInstant(rs.getTimestamp("updated_at"))
        );
    }

    private static Instant toInstant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }
}
