package com.agentbench.analytics.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * Comment on a published public leaderboard (anonymous or authenticated).
 */
public record LeaderboardComment(
        UUID id,
        UUID orgId,
        UUID publicationId,
        String body,
        String displayName,
        UUID authorUserId,
        Instant createdAt,
        Instant deletedAt,
        UUID deletedBy
) {
    public boolean isDeleted() {
        return deletedAt != null;
    }
}
