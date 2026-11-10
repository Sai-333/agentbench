package com.agentbench.analytics.api;

import com.agentbench.analytics.api.dto.AnalyticsDtos;
import com.agentbench.analytics.service.AnalyticsService;
import com.agentbench.analytics.service.EmbedHtmlRenderer;
import com.agentbench.analytics.service.PublicLeaderboardCacheSupport;
import com.agentbench.platform.api.ApiPaths;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(ApiPaths.API_V1 + "/public/leaderboards")
@Tag(name = "Public leaderboards", description = "Unauthenticated read of explicitly published leaderboards + signed embeds")
public class PublicLeaderboardController {

    private final AnalyticsService analyticsService;
    private final PublicLeaderboardCacheSupport cacheSupport;
    private final EmbedHtmlRenderer embedHtmlRenderer;

    public PublicLeaderboardController(
            AnalyticsService analyticsService,
            PublicLeaderboardCacheSupport cacheSupport,
            EmbedHtmlRenderer embedHtmlRenderer) {
        this.analyticsService = analyticsService;
        this.cacheSupport = cacheSupport;
        this.embedHtmlRenderer = embedHtmlRenderer;
    }

    @GetMapping("/{publicSlug}")
    @Operation(summary = "Fetch a published public leaderboard by stable slug (404 if unpublished)")
    public ResponseEntity<AnalyticsDtos.PublicLeaderboardResponse> getPublic(
            @PathVariable String publicSlug,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return cacheSupport.json(analyticsService.publicLeaderboard(publicSlug, page, size));
    }

    @GetMapping(value = "/{publicSlug}/embed", produces = {
            MediaType.APPLICATION_JSON_VALUE,
            MediaType.TEXT_HTML_VALUE
    })
    @Operation(summary = "Signed embed read — validates HMAC token + expiry; JSON or HTML for iframe")
    public ResponseEntity<?> getEmbed(
            @PathVariable String publicSlug,
            @RequestParam String token,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false, defaultValue = "json") String format) {
        AnalyticsDtos.PublicLeaderboardResponse board =
                analyticsService.publicLeaderboardEmbed(publicSlug, token, page, size);
        if ("html".equalsIgnoreCase(format == null ? "json" : format.trim())) {
            return cacheSupport.html(embedHtmlRenderer.render(board), board);
        }
        return cacheSupport.json(board);
    }
}
