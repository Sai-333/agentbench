package com.agentbench.analytics.service;

import com.agentbench.analytics.api.dto.AnalyticsDtos;
import com.agentbench.analytics.config.PublicLeaderboardProperties;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.util.DigestUtils;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * CDN-friendly Cache-Control + weak ETag for public / embed leaderboard reads.
 * Does not require a real CDN — any reverse proxy can honor these headers.
 */
@Component
public class PublicLeaderboardCacheSupport {

    private final PublicLeaderboardProperties properties;

    public PublicLeaderboardCacheSupport(PublicLeaderboardProperties properties) {
        this.properties = properties;
    }

    public ResponseEntity<AnalyticsDtos.PublicLeaderboardResponse> json(
            AnalyticsDtos.PublicLeaderboardResponse body) {
        String etag = weakEtag(body);
        return ResponseEntity.ok()
                .cacheControl(cacheControl())
                .eTag(etag)
                .header(HttpHeaders.VARY, "Accept")
                .body(body);
    }

    public ResponseEntity<String> html(String html, AnalyticsDtos.PublicLeaderboardResponse board) {
        String etag = weakEtag(board) + "-html";
        return ResponseEntity.ok()
                .cacheControl(cacheControl())
                .eTag(etag)
                .header(HttpHeaders.CONTENT_TYPE, "text/html;charset=UTF-8")
                .header(HttpHeaders.VARY, "Accept")
                // Intentionally frameable for portfolio / third-party embeds.
                .header("Content-Security-Policy", "frame-ancestors *")
                .body(html);
    }

    public CacheControl cacheControl() {
        int maxAge = Math.max(0, properties.getCacheMaxAgeSeconds());
        int swr = Math.max(0, properties.getCacheStaleWhileRevalidateSeconds());
        CacheControl cc = CacheControl.maxAge(Duration.ofSeconds(maxAge)).cachePublic();
        if (swr > 0) {
            cc = cc.staleWhileRevalidate(swr, TimeUnit.SECONDS);
        }
        return cc;
    }

    static String weakEtag(AnalyticsDtos.PublicLeaderboardResponse body) {
        String fingerprint = String.join("|",
                body.publicSlug() == null ? "" : body.publicSlug(),
                body.publishedAt() == null ? "" : body.publishedAt().toString(),
                body.title() == null ? "" : body.title(),
                String.valueOf(body.page() == null ? 0 : body.page().page()),
                String.valueOf(body.page() == null ? 0 : body.page().size()),
                String.valueOf(body.page() == null ? 0 : body.page().total()),
                String.valueOf(body.entries() == null ? 0 : body.entries().size()),
                body.entries() == null || body.entries().isEmpty()
                        ? ""
                        : body.entries().get(0).agentName() + ":" + body.entries().get(0).weightedTotal()
        );
        String hash = DigestUtils.md5DigestAsHex(fingerprint.getBytes(StandardCharsets.UTF_8));
        return "W/\"" + hash + "\"";
    }
}
