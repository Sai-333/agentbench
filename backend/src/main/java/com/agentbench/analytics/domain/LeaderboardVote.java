package com.agentbench.analytics.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * Board-level up (+1) / down (-1) vote on a published public leaderboard.
 */
public record LeaderboardVote(
        UUID id,
        UUID orgId,
        UUID publicationId,
        String voterKey,
        int value,
        UUID authorUserId,
        Instant createdAt,
        Instant updatedAt
) {
}
