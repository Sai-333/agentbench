package com.agentbench.analytics.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * Opt-in publication of an org-private leaderboard to a public slug.
 */
public record LeaderboardPublication(
        UUID id,
        UUID orgId,
        UUID benchmarkVersionId,
        String publicSlug,
        String title,
        Instant publishedAt,
        UUID publishedBy,
        Instant unpublishedAt,
        UUID unpublishedBy,
        Instant createdAt,
        Instant updatedAt
) {
    public boolean isActive() {
        return unpublishedAt == null;
    }
}
