package com.agentbench.analytics.api;

import com.agentbench.analytics.api.dto.AnalyticsDtos;
import com.agentbench.analytics.service.AnalyticsService;
import com.agentbench.platform.api.ApiPaths;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(ApiPaths.API_V1 + "/public/leaderboards")
@Tag(name = "Public leaderboards", description = "Unauthenticated read of explicitly published leaderboards")
public class PublicLeaderboardController {

    private final AnalyticsService analyticsService;

    public PublicLeaderboardController(AnalyticsService analyticsService) {
        this.analyticsService = analyticsService;
    }

    @GetMapping("/{publicSlug}")
    @Operation(summary = "Fetch a published public leaderboard by stable slug (404 if unpublished)")
    public AnalyticsDtos.PublicLeaderboardResponse getPublic(
            @PathVariable String publicSlug,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return analyticsService.publicLeaderboard(publicSlug, page, size);
    }
}
