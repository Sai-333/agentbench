package com.agentbench.analytics.service;

import com.agentbench.analytics.api.dto.AnalyticsDtos;
import com.agentbench.analytics.infra.AnalyticsRepository;
import com.agentbench.evaluation.api.dto.EvaluationDtos;
import com.agentbench.evaluation.service.EvaluationService;
import com.agentbench.identity.domain.OrgRole;
import com.agentbench.identity.security.OrgAccessService;
import com.agentbench.platform.error.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.actuate.health.CompositeHealth;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthComponent;
import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.boot.actuate.health.Status;
import org.springframework.boot.actuate.health.SystemHealth;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class AnalyticsService {

    private static final Set<String> TERMINAL = Set.of("COMPLETED", "FAILED", "CANCELLED", "TIMED_OUT");
    private static final Set<String> QUEUED = Set.of("QUEUED");

    private final AnalyticsRepository analyticsRepository;
    private final OrgAccessService orgAccessService;
    private final EvaluationService evaluationService;
    private final ReportExportService reportExportService;
    private final HealthEndpoint healthEndpoint;
    private final ObjectMapper objectMapper;

    public AnalyticsService(
            AnalyticsRepository analyticsRepository,
            OrgAccessService orgAccessService,
            EvaluationService evaluationService,
            ReportExportService reportExportService,
            HealthEndpoint healthEndpoint,
            ObjectMapper objectMapper) {
        this.analyticsRepository = analyticsRepository;
        this.orgAccessService = orgAccessService;
        this.evaluationService = evaluationService;
        this.reportExportService = reportExportService;
        this.healthEndpoint = healthEndpoint;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public AnalyticsDtos.OverviewResponse overview(UUID orgId, Integer recentLimit, Integer days) {
        orgAccessService.requireMembership(orgId, OrgRole.VIEWER);
        Map<String, Long> byState = analyticsRepository.countsByState(orgId);
        long total = byState.values().stream().mapToLong(Long::longValue).sum();
        long completed = byState.getOrDefault("COMPLETED", 0L);
        long failed = byState.getOrDefault("FAILED", 0L);
        long cancelled = byState.getOrDefault("CANCELLED", 0L);
        long timedOut = byState.getOrDefault("TIMED_OUT", 0L);
        long queued = byState.getOrDefault("QUEUED", 0L);
        long inFlight = byState.entrySet().stream()
                .filter(e -> !TERMINAL.contains(e.getKey()) && !QUEUED.contains(e.getKey()))
                .mapToLong(Map.Entry::getValue)
                .sum();
        return new AnalyticsDtos.OverviewResponse(
                total,
                completed,
                failed,
                cancelled,
                timedOut,
                inFlight,
                queued,
                analyticsRepository.averageWeightedTotal(orgId),
                analyticsRepository.totalEstimatedCost(orgId),
                analyticsRepository.totalTokens(orgId),
                byState,
                analyticsRepository.recentEvaluations(orgId, recentLimit == null ? 10 : recentLimit),
                analyticsRepository.volumeByDay(orgId, days == null ? 14 : days)
        );
    }

    @Transactional(readOnly = true)
    public AnalyticsDtos.LeaderboardResponse leaderboard(
            UUID orgId,
            UUID benchmarkVersionId,
            UUID taskId,
            Integer page,
            Integer size) {
        orgAccessService.requireMembership(orgId, OrgRole.VIEWER);
        requireVersion(orgId, benchmarkVersionId);
        int pageNum = page == null || page < 0 ? 0 : page;
        int pageSize = size == null ? 25 : Math.min(Math.max(size, 1), 100);
        long total = analyticsRepository.countLeaderboard(orgId, benchmarkVersionId, taskId);
        List<AnalyticsDtos.LeaderboardEntry> entries = analyticsRepository.leaderboard(
                orgId, benchmarkVersionId, taskId, pageSize, pageNum * pageSize);
        return new AnalyticsDtos.LeaderboardResponse(
                benchmarkVersionId,
                analyticsRepository.scoringProfileKey(orgId, benchmarkVersionId),
                "Organization-private leaderboard. Same benchmark version only; best score per agent×task.",
                entries,
                new AnalyticsDtos.PageMeta(pageNum, pageSize, total)
        );
    }

    @Transactional(readOnly = true)
    public AnalyticsDtos.ComparisonResponse comparisons(
            UUID orgId,
            UUID benchmarkVersionId,
            String agentIdsCsv,
            UUID taskId) {
        orgAccessService.requireMembership(orgId, OrgRole.VIEWER);
        requireVersion(orgId, benchmarkVersionId);
        List<UUID> agentIds = parseAgentIds(agentIdsCsv);
        if (agentIds.isEmpty()) {
            throw ApiException.validation("Provide at least one agentId (comma-separated agentIds query param)");
        }
        if (agentIds.size() > 20) {
            throw ApiException.validation("Compare at most 20 agents at a time");
        }
        List<AnalyticsDtos.ComparisonRow> rows = analyticsRepository.comparisons(
                orgId, benchmarkVersionId, agentIds, taskId);
        LinkedHashSet<String> metricKeys = new LinkedHashSet<>();
        for (AnalyticsDtos.ComparisonRow row : rows) {
            metricKeys.addAll(row.metricRaws().keySet());
        }
        return new AnalyticsDtos.ComparisonResponse(
                benchmarkVersionId,
                analyticsRepository.scoringProfileKey(orgId, benchmarkVersionId),
                agentIds,
                rows,
                new ArrayList<>(metricKeys)
        );
    }

    @Transactional(readOnly = true)
    public AnalyticsDtos.CostAnalyticsResponse costs(
            UUID orgId,
            UUID agentId,
            Instant from,
            Instant to,
            Integer page,
            Integer size) {
        orgAccessService.requireMembership(orgId, OrgRole.VIEWER);
        AnalyticsRepository.CostTotals totals = analyticsRepository.costTotals(orgId, agentId, from, to);
        List<AnalyticsDtos.CostByAgent> byAgent = analyticsRepository.costsByAgent(orgId, agentId, from, to);
        int pageNum = page == null || page < 0 ? 0 : page;
        int pageSize = size == null ? 50 : Math.min(Math.max(size, 1), 100);
        int fromIdx = Math.min(pageNum * pageSize, byAgent.size());
        int toIdx = Math.min(fromIdx + pageSize, byAgent.size());
        return new AnalyticsDtos.CostAnalyticsResponse(
                totals.totalCost() == null ? BigDecimal.ZERO : totals.totalCost(),
                totals.promptTokens(),
                totals.completionTokens(),
                totals.totalTokens(),
                totals.invocationCount(),
                byAgent.subList(fromIdx, toIdx),
                analyticsRepository.costsByDay(orgId, agentId, from, to),
                new AnalyticsDtos.PageMeta(pageNum, pageSize, byAgent.size())
        );
    }

    @Transactional(readOnly = true)
    public AnalyticsDtos.EvaluationSearchResponse searchEvaluations(
            UUID orgId,
            String state,
            UUID agentId,
            UUID taskId,
            UUID benchmarkVersionId,
            String q,
            Integer page,
            Integer size) {
        orgAccessService.requireMembership(orgId, OrgRole.VIEWER);
        int pageNum = page == null || page < 0 ? 0 : page;
        int pageSize = size == null ? 25 : Math.min(Math.max(size, 1), 100);
        long total = analyticsRepository.countEvaluations(
                orgId, state, agentId, taskId, benchmarkVersionId, q);
        List<AnalyticsDtos.EvaluationSearchItem> items = analyticsRepository.searchEvaluations(
                orgId, state, agentId, taskId, benchmarkVersionId, q, pageSize, pageNum * pageSize);
        return new AnalyticsDtos.EvaluationSearchResponse(
                items,
                new AnalyticsDtos.PageMeta(pageNum, pageSize, total)
        );
    }

    @Transactional(readOnly = true)
    public AnalyticsDtos.SystemHealthResponse systemHealth(UUID orgId) {
        orgAccessService.requireMembership(orgId, OrgRole.VIEWER);
        HealthComponent health = healthEndpoint.health();
        Map<String, AnalyticsDtos.ComponentHealth> components = new LinkedHashMap<>();
        String overall = health.getStatus().getCode();
        Map<String, HealthComponent> nested = Map.of();
        if (health instanceof SystemHealth systemHealth) {
            overall = systemHealth.getStatus().getCode();
            nested = systemHealth.getComponents();
        } else if (health instanceof CompositeHealth composite) {
            overall = composite.getStatus().getCode();
            nested = composite.getComponents();
        } else if (health instanceof Health simple) {
            components.put("application", new AnalyticsDtos.ComponentHealth(
                    simple.getStatus().getCode(),
                    String.valueOf(simple.getDetails())
            ));
        }
        nested.forEach((name, component) ->
                components.put(name, new AnalyticsDtos.ComponentHealth(
                        component.getStatus().getCode(),
                        detailOf(component)
                )));
        Map<String, Object> counts = new LinkedHashMap<>();
        counts.put("activeEvaluations", analyticsRepository.countActiveEvaluations(orgId));
        counts.put("enabledAgents", analyticsRepository.countAgents(orgId));
        counts.put("benchmarks", analyticsRepository.countBenchmarks(orgId));
        counts.put("totalEvaluations", analyticsRepository.countsByState(orgId).values().stream()
                .mapToLong(Long::longValue).sum());
        return new AnalyticsDtos.SystemHealthResponse(
                overall == null ? Status.UNKNOWN.getCode() : overall,
                Instant.now(),
                components,
                counts
        );
    }

    @Transactional(readOnly = true)
    public byte[] exportEvaluation(UUID orgId, UUID evaluationId, String format) {
        orgAccessService.requireMembership(orgId, OrgRole.VIEWER);
        EvaluationDtos.EvaluationReportResponse report = evaluationService.report(orgId, evaluationId);
        String normalized = format == null ? "json" : format.trim().toLowerCase();
        return switch (normalized) {
            case "csv" -> reportExportService.toCsv(report).getBytes(StandardCharsets.UTF_8);
            case "json" -> reportExportService.toJson(report).getBytes(StandardCharsets.UTF_8);
            default -> throw ApiException.validation("Unsupported export format: " + format + " (use json or csv)");
        };
    }

    @Transactional(readOnly = true)
    public byte[] exportLeaderboard(UUID orgId, UUID benchmarkVersionId, UUID taskId, String format) {
        AnalyticsDtos.LeaderboardResponse board = leaderboard(orgId, benchmarkVersionId, taskId, 0, 100);
        String normalized = format == null ? "csv" : format.trim().toLowerCase();
        return switch (normalized) {
            case "csv" -> reportExportService.leaderboardCsv(board.entries()).getBytes(StandardCharsets.UTF_8);
            case "json" -> {
                try {
                    yield objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(board);
                } catch (Exception ex) {
                    throw new IllegalStateException("Failed to serialize leaderboard", ex);
                }
            }
            default -> throw ApiException.validation("Unsupported export format: " + format + " (use json or csv)");
        };
    }

    private void requireVersion(UUID orgId, UUID benchmarkVersionId) {
        if (benchmarkVersionId == null) {
            throw ApiException.validation("benchmarkVersionId is required");
        }
        if (!analyticsRepository.benchmarkVersionExists(orgId, benchmarkVersionId)) {
            throw ApiException.notFound("Benchmark version not found");
        }
    }

    private static List<UUID> parseAgentIds(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        return Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(UUID::fromString)
                .collect(Collectors.toCollection(ArrayList::new));
    }

    private static String detailOf(HealthComponent component) {
        if (component instanceof Health health) {
            Object error = health.getDetails().get("error");
            if (error != null) {
                return String.valueOf(error);
            }
            if (!health.getDetails().isEmpty()) {
                return health.getDetails().toString();
            }
        }
        return component.getStatus().getCode();
    }
}
