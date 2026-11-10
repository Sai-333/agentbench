package com.agentbench.analytics.service;

import com.agentbench.analytics.config.PublicLeaderboardProperties;
import com.agentbench.platform.error.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Simple in-memory sliding-window rate limiter for anonymous-capable public writes.
 * Per-process only (Compose/single API replica); not a distributed limiter.
 */
@Component
public class PublicEngagementRateLimiter {

    public enum Action {
        COMMENT,
        VOTE
    }

    private final PublicLeaderboardProperties properties;
    private final Clock clock;
    private final ConcurrentHashMap<String, Deque<Long>> windows = new ConcurrentHashMap<>();

    public PublicEngagementRateLimiter(PublicLeaderboardProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    public void check(Action action, String clientKey) {
        int limit;
        long windowMs;
        if (action == Action.COMMENT) {
            limit = properties.getCommentRateLimit();
            windowMs = properties.getCommentRateWindowSeconds() * 1000L;
        } else {
            limit = properties.getVoteRateLimit();
            windowMs = properties.getVoteRateWindowSeconds() * 1000L;
        }
        if (limit <= 0 || windowMs <= 0) {
            return;
        }
        String key = action.name() + ":" + (clientKey == null || clientKey.isBlank() ? "unknown" : clientKey);
        long now = clock.millis();
        long cutoff = now - windowMs;
        Deque<Long> deque = windows.computeIfAbsent(key, ignored -> new ArrayDeque<>());
        synchronized (deque) {
            prune(deque, cutoff);
            if (deque.size() >= limit) {
                throw new ApiException(
                        HttpStatus.TOO_MANY_REQUESTS,
                        "RATE_LIMITED",
                        "Too many " + action.name().toLowerCase() + " requests; try again later");
            }
            deque.addLast(now);
        }
        maybeTrimMap();
    }

    private static void prune(Deque<Long> deque, long cutoff) {
        while (!deque.isEmpty() && deque.peekFirst() < cutoff) {
            deque.removeFirst();
        }
    }

    private void maybeTrimMap() {
        if (windows.size() < 10_000) {
            return;
        }
        long now = clock.millis();
        Iterator<Map.Entry<String, Deque<Long>>> it = windows.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Deque<Long>> entry = it.next();
            Deque<Long> deque = entry.getValue();
            synchronized (deque) {
                prune(deque, now - Math.max(
                        properties.getCommentRateWindowSeconds(),
                        properties.getVoteRateWindowSeconds()) * 1000L);
                if (deque.isEmpty()) {
                    it.remove();
                }
            }
        }
    }
}
