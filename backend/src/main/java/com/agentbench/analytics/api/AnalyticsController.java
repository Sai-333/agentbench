package com.agentbench.analytics.api;

import com.agentbench.analytics.api.dto.AnalyticsDtos;
import com.agentbench.analytics.service.AnalyticsService;
import com.agentbench.platform.api.ApiPaths;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.UUID;

@RestController
@RequestMapping(ApiPaths.ORG_BASE)
@Tag(name = "Analytics", description = "Org-private dashboards, leaderboards, comparisons, costs, exports; opt-in public publish")
@SecurityRequirement(name = "bearerAuth")
public class AnalyticsController {

    private final AnalyticsService analyticsService;

    public AnalyticsController(AnalyticsService analyticsService) {
        this.analyticsService = analyticsService;
    }

    @GetMapping("/analytics/overview")
    @Operation(summary = "Evaluation overview aggregates for the organization")
    public AnalyticsDtos.OverviewResponse overview(
            @PathVariable UUID orgId,
            @RequestParam(required = false) Integer recentLimit,
            @RequestParam(required = false) Integer days) {
        return analyticsService.overview(orgId, recentLimit, days);
    }

    @GetMapping("/analytics/evaluations")
    @Operation(summary = "Search and paginate evaluations with filters")
    public AnalyticsDtos.EvaluationSearchResponse searchEvaluations(
            @PathVariable UUID orgId,
            @RequestParam(required = false) String state,
            @RequestParam(required = false) UUID agentId,
            @RequestParam(required = false) UUID taskId,
            @RequestParam(required = false) UUID benchmarkVersionId,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return analyticsService.searchEvaluations(
                orgId, state, agentId, taskId, benchmarkVersionId, q, page, size);
    }

    @GetMapping("/benchmark-versions/{versionId}/leaderboard")
    @Operation(summary = "Org-private leaderboard for a benchmark version (default; not public)")
    public AnalyticsDtos.LeaderboardResponse leaderboard(
            @PathVariable UUID orgId,
            @PathVariable UUID versionId,
            @RequestParam(required = false) UUID taskId,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return analyticsService.leaderboard(orgId, versionId, taskId, page, size);
    }

    @GetMapping("/benchmark-versions/{versionId}/leaderboard/publication")
    @Operation(summary = "Publication status for this version's leaderboard (org members)")
    public AnalyticsDtos.LeaderboardPublicationStatus publicationStatus(
            @PathVariable UUID orgId,
            @PathVariable UUID versionId) {
        return analyticsService.publicationStatus(orgId, versionId);
    }

    @PostMapping("/benchmark-versions/{versionId}/leaderboard/publish")
    @Operation(summary = "Publish leaderboard snapshot to a public slug (OWNER/ADMIN)")
    public AnalyticsDtos.LeaderboardPublicationStatus publishLeaderboard(
            @PathVariable UUID orgId,
            @PathVariable UUID versionId,
            @RequestBody(required = false) AnalyticsDtos.PublishLeaderboardRequest request) {
        return analyticsService.publishLeaderboard(orgId, versionId, request);
    }

    @PostMapping("/benchmark-versions/{versionId}/leaderboard/unpublish")
    @Operation(summary = "Revoke public leaderboard publication (OWNER/ADMIN)")
    public AnalyticsDtos.LeaderboardPublicationStatus unpublishLeaderboard(
            @PathVariable UUID orgId,
            @PathVariable UUID versionId) {
        return analyticsService.unpublishLeaderboard(orgId, versionId);
    }

    @GetMapping(value = "/benchmark-versions/{versionId}/leaderboard/export", produces = {
            "text/csv", MediaType.APPLICATION_JSON_VALUE, MediaType.APPLICATION_PDF_VALUE
    })
    @Operation(summary = "Export org-private leaderboard as CSV, JSON, or PDF")
    public ResponseEntity<byte[]> exportLeaderboard(
            @PathVariable UUID orgId,
            @PathVariable UUID versionId,
            @RequestParam(required = false) UUID taskId,
            @RequestParam(defaultValue = "csv") String format) {
        byte[] body = analyticsService.exportLeaderboard(orgId, versionId, taskId, format);
        return attachment(
                body,
                "leaderboard-" + versionId,
                format,
                "csv");
    }

    @GetMapping("/analytics/comparisons")
    @Operation(summary = "Compare agents on an identical benchmark version")
    public AnalyticsDtos.ComparisonResponse comparisons(
            @PathVariable UUID orgId,
            @RequestParam UUID benchmarkVersionId,
            @RequestParam String agentIds,
            @RequestParam(required = false) UUID taskId) {
        return analyticsService.comparisons(orgId, benchmarkVersionId, agentIds, taskId);
    }

    @GetMapping("/analytics/costs")
    @Operation(summary = "Token and estimated cost analytics where recorded")
    public AnalyticsDtos.CostAnalyticsResponse costs(
            @PathVariable UUID orgId,
            @RequestParam(required = false) UUID agentId,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return analyticsService.costs(orgId, agentId, from, to, page, size);
    }

    @GetMapping("/analytics/health")
    @Operation(summary = "Actuator health summary plus org workload counts (ops view)")
    public AnalyticsDtos.SystemHealthResponse health(@PathVariable UUID orgId) {
        return analyticsService.systemHealth(orgId);
    }

    @GetMapping(value = "/evaluations/{evaluationId}/export", produces = {
            MediaType.APPLICATION_JSON_VALUE, "text/csv", MediaType.APPLICATION_PDF_VALUE
    })
    @Operation(summary = "Download evaluation report as JSON, CSV, or PDF")
    public ResponseEntity<byte[]> exportEvaluation(
            @PathVariable UUID orgId,
            @PathVariable UUID evaluationId,
            @RequestParam(defaultValue = "json") String format) {
        byte[] body = analyticsService.exportEvaluation(orgId, evaluationId, format);
        return attachment(
                body,
                "evaluation-" + evaluationId,
                format,
                "json");
    }

    private static ResponseEntity<byte[]> attachment(
            byte[] body,
            String filenameBase,
            String format,
            String defaultFormat) {
        String normalized = format == null || format.isBlank()
                ? defaultFormat
                : format.trim().toLowerCase();
        String extension;
        MediaType mediaType;
        switch (normalized) {
            case "csv" -> {
                extension = ".csv";
                mediaType = MediaType.parseMediaType("text/csv");
            }
            case "pdf" -> {
                extension = ".pdf";
                mediaType = MediaType.APPLICATION_PDF;
            }
            default -> {
                extension = ".json";
                mediaType = MediaType.APPLICATION_JSON;
            }
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + filenameBase + extension + "\"")
                .contentType(mediaType)
                .body(body);
    }
}
