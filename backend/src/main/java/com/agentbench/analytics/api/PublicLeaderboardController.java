package com.agentbench.analytics.api;

import com.agentbench.analytics.api.dto.AnalyticsDtos;
import com.agentbench.analytics.service.AnalyticsService;
import com.agentbench.analytics.service.ClientIpResolver;
import com.agentbench.analytics.service.EmbedHtmlRenderer;
import com.agentbench.analytics.service.PublicEngagementService;
import com.agentbench.analytics.service.PublicLeaderboardCacheSupport;
import com.agentbench.platform.api.ApiPaths;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(ApiPaths.API_V1 + "/public/leaderboards")
@Tag(name = "Public leaderboards",
        description = "Unauthenticated read of published leaderboards + signed embeds; anonymous-capable comments/votes")
public class PublicLeaderboardController {

    private final AnalyticsService analyticsService;
    private final PublicLeaderboardCacheSupport cacheSupport;
    private final EmbedHtmlRenderer embedHtmlRenderer;
    private final PublicEngagementService engagementService;
    private final ClientIpResolver clientIpResolver;

    public PublicLeaderboardController(
            AnalyticsService analyticsService,
            PublicLeaderboardCacheSupport cacheSupport,
            EmbedHtmlRenderer embedHtmlRenderer,
            PublicEngagementService engagementService,
            ClientIpResolver clientIpResolver) {
        this.analyticsService = analyticsService;
        this.cacheSupport = cacheSupport;
        this.embedHtmlRenderer = embedHtmlRenderer;
        this.engagementService = engagementService;
        this.clientIpResolver = clientIpResolver;
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
            AnalyticsDtos.PublicVoteSummaryResponse votes = engagementService.voteSummary(publicSlug, null);
            AnalyticsDtos.PublicCommentListResponse comments =
                    engagementService.listComments(publicSlug, 0, 5);
            return cacheSupport.html(embedHtmlRenderer.render(board, votes, comments), board);
        }
        return cacheSupport.json(board);
    }

    @GetMapping("/{publicSlug}/comments")
    @Operation(summary = "List active comments on a published public leaderboard")
    public AnalyticsDtos.PublicCommentListResponse listComments(
            @PathVariable String publicSlug,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return engagementService.listComments(publicSlug, page, size);
    }

    @PostMapping("/{publicSlug}/comments")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Post a comment (anonymous or authenticated) on a published public leaderboard")
    public AnalyticsDtos.PublicCommentResponse createComment(
            @PathVariable String publicSlug,
            @RequestBody(required = false) AnalyticsDtos.CreatePublicCommentRequest request,
            HttpServletRequest httpRequest) {
        return engagementService.createComment(publicSlug, request, clientIpResolver.resolve(httpRequest));
    }

    @GetMapping("/{publicSlug}/votes")
    @Operation(summary = "Board-level up/down vote tallies for a published public leaderboard")
    public AnalyticsDtos.PublicVoteSummaryResponse voteSummary(
            @PathVariable String publicSlug,
            @RequestParam(required = false) String voterFingerprint) {
        return engagementService.voteSummary(publicSlug, voterFingerprint);
    }

    @PostMapping("/{publicSlug}/votes")
    @Operation(summary = "Cast or change an up/down vote (anonymous fingerprint or authenticated user)")
    public AnalyticsDtos.PublicVoteSummaryResponse castVote(
            @PathVariable String publicSlug,
            @RequestBody(required = false) AnalyticsDtos.CastPublicVoteRequest request,
            HttpServletRequest httpRequest) {
        return engagementService.castVote(publicSlug, request, clientIpResolver.resolve(httpRequest));
    }
}
