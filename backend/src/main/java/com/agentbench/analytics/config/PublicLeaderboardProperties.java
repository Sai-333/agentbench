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

    /** Max comment body length (chars). */
    private int maxCommentBodyLength = 2000;

    /** Max display name length (chars). */
    private int maxDisplayNameLength = 80;

    /** Anonymous/auth comment rate limit per client key (IP) per window. */
    private int commentRateLimit = 5;

    /** Comment rate window (seconds). */
    private int commentRateWindowSeconds = 600;

    /** Vote upsert rate limit per client key (IP) per window. */
    private int voteRateLimit = 30;

    /** Vote rate window (seconds). */
    private int voteRateWindowSeconds = 600;

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

    public int getMaxCommentBodyLength() {
        return maxCommentBodyLength;
    }

    public void setMaxCommentBodyLength(int maxCommentBodyLength) {
        this.maxCommentBodyLength = maxCommentBodyLength;
    }

    public int getMaxDisplayNameLength() {
        return maxDisplayNameLength;
    }

    public void setMaxDisplayNameLength(int maxDisplayNameLength) {
        this.maxDisplayNameLength = maxDisplayNameLength;
    }

    public int getCommentRateLimit() {
        return commentRateLimit;
    }

    public void setCommentRateLimit(int commentRateLimit) {
        this.commentRateLimit = commentRateLimit;
    }

    public int getCommentRateWindowSeconds() {
        return commentRateWindowSeconds;
    }

    public void setCommentRateWindowSeconds(int commentRateWindowSeconds) {
        this.commentRateWindowSeconds = commentRateWindowSeconds;
    }

    public int getVoteRateLimit() {
        return voteRateLimit;
    }

    public void setVoteRateLimit(int voteRateLimit) {
        this.voteRateLimit = voteRateLimit;
    }

    public int getVoteRateWindowSeconds() {
        return voteRateWindowSeconds;
    }

    public void setVoteRateWindowSeconds(int voteRateWindowSeconds) {
        this.voteRateWindowSeconds = voteRateWindowSeconds;
    }
}
