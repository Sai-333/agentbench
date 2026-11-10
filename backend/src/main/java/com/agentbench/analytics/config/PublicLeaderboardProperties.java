package com.agentbench.analytics.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Public leaderboard / signed-embed knobs. CDN is not required — Cache-Control is CDN-friendly.
 */
@ConfigurationProperties(prefix = "agentbench.public-leaderboards")
public class PublicLeaderboardProperties {

    /**
     * HMAC secret for embed tokens. Falls back to {@code agentbench.jwt.secret} when blank.
     * Must be at least 32 characters after resolution.
     */
    private String embedSecret = "";

    /** Default embed token TTL (seconds). */
    private long defaultEmbedTtlSeconds = 604_800L;

    /** Maximum embed token TTL (seconds). */
    private long maxEmbedTtlSeconds = 7_776_000L;

    /** Cache-Control max-age for public + embed GETs (seconds). */
    private int cacheMaxAgeSeconds = 60;

    /** Optional stale-while-revalidate hint (seconds); 0 disables. */
    private int cacheStaleWhileRevalidateSeconds = 300;

    public String getEmbedSecret() {
        return embedSecret;
    }

    public void setEmbedSecret(String embedSecret) {
        this.embedSecret = embedSecret;
    }

    public long getDefaultEmbedTtlSeconds() {
        return defaultEmbedTtlSeconds;
    }

    public void setDefaultEmbedTtlSeconds(long defaultEmbedTtlSeconds) {
        this.defaultEmbedTtlSeconds = defaultEmbedTtlSeconds;
    }

    public long getMaxEmbedTtlSeconds() {
        return maxEmbedTtlSeconds;
    }

    public void setMaxEmbedTtlSeconds(long maxEmbedTtlSeconds) {
        this.maxEmbedTtlSeconds = maxEmbedTtlSeconds;
    }

    public int getCacheMaxAgeSeconds() {
        return cacheMaxAgeSeconds;
    }

    public void setCacheMaxAgeSeconds(int cacheMaxAgeSeconds) {
        this.cacheMaxAgeSeconds = cacheMaxAgeSeconds;
    }

    public int getCacheStaleWhileRevalidateSeconds() {
        return cacheStaleWhileRevalidateSeconds;
    }

    public void setCacheStaleWhileRevalidateSeconds(int cacheStaleWhileRevalidateSeconds) {
        this.cacheStaleWhileRevalidateSeconds = cacheStaleWhileRevalidateSeconds;
    }
}
