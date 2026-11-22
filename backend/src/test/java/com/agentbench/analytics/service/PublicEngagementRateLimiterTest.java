package com.agentbench.analytics.service;

import com.agentbench.analytics.config.PublicLeaderboardProperties;
import com.agentbench.platform.error.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * REG-RATE-LIMIT: 5 comments and 30 votes per 600 seconds, 429 {@code RATE_LIMITED}.
 * The window is per limiter instance ({@code ConcurrentHashMap}), not shared across processes.
 */
class PublicEngagementRateLimiterTest {

    @Test
    void defaultsAreFiveCommentsAndThirtyVotesPerSixHundredSeconds() {
        PublicLeaderboardProperties properties = new PublicLeaderboardProperties();
        assertEquals(5, properties.getCommentRateLimit());
        assertEquals(30, properties.getVoteRateLimit());
        assertEquals(600, properties.getCommentRateWindowSeconds());
        assertEquals(600, properties.getVoteRateWindowSeconds());
    }

    @Test
    void sixthCommentAndThirtyFirstVoteAreRateLimitedUntilTheWindowSlides() {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        PublicEngagementRateLimiter limiter = limiter(clock);
        String client = "203.0.113.10";

        for (int i = 0; i < 5; i++) {
            assertDoesNotThrow(() -> limiter.check(PublicEngagementRateLimiter.Action.COMMENT, client));
        }
        ApiException commentLimited = assertThrows(ApiException.class,
                () -> limiter.check(PublicEngagementRateLimiter.Action.COMMENT, client));
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, commentLimited.getStatus());
        assertEquals("RATE_LIMITED", commentLimited.getCode());
        assertTrue(commentLimited.getMessage().contains("comment"));

        for (int i = 0; i < 30; i++) {
            assertDoesNotThrow(() -> limiter.check(PublicEngagementRateLimiter.Action.VOTE, client));
        }
        ApiException voteLimited = assertThrows(ApiException.class,
                () -> limiter.check(PublicEngagementRateLimiter.Action.VOTE, client));
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, voteLimited.getStatus());
        assertEquals("RATE_LIMITED", voteLimited.getCode());
        assertTrue(voteLimited.getMessage().contains("vote"));

        assertDoesNotThrow(() -> limiter.check(PublicEngagementRateLimiter.Action.COMMENT, "203.0.113.11"));
        assertDoesNotThrow(() -> limiter.check(PublicEngagementRateLimiter.Action.VOTE, "203.0.113.11"));

        clock.advance(Duration.ofSeconds(600));
        assertThrows(ApiException.class,
                () -> limiter.check(PublicEngagementRateLimiter.Action.COMMENT, client));
        assertThrows(ApiException.class,
                () -> limiter.check(PublicEngagementRateLimiter.Action.VOTE, client));

        clock.advance(Duration.ofMillis(1));
        assertDoesNotThrow(() -> limiter.check(PublicEngagementRateLimiter.Action.COMMENT, client));
        assertDoesNotThrow(() -> limiter.check(PublicEngagementRateLimiter.Action.VOTE, client));
    }

    @Test
    void secondLimiterDoesNotSeeTheFirstProcessWindow() {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        PublicLeaderboardProperties properties = new PublicLeaderboardProperties();
        PublicEngagementRateLimiter first = new PublicEngagementRateLimiter(properties, clock);
        PublicEngagementRateLimiter second = new PublicEngagementRateLimiter(properties, clock);
        String client = "203.0.113.10";

        for (int i = 0; i < 5; i++) {
            first.check(PublicEngagementRateLimiter.Action.COMMENT, client);
        }
        assertEquals("RATE_LIMITED", assertThrows(ApiException.class,
                () -> first.check(PublicEngagementRateLimiter.Action.COMMENT, client)).getCode());

        assertDoesNotThrow(() -> second.check(PublicEngagementRateLimiter.Action.COMMENT, client));
    }

    private static PublicEngagementRateLimiter limiter(Clock clock) {
        return new PublicEngagementRateLimiter(new PublicLeaderboardProperties(), clock);
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
