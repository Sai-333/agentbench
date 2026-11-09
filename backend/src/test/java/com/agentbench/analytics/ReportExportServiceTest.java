package com.agentbench.analytics;

import com.agentbench.analytics.api.dto.AnalyticsDtos;
import com.agentbench.analytics.service.ReportExportService;
import com.agentbench.evaluation.api.dto.EvaluationDtos;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ReportExportServiceTest {

    private final ReportExportService service = new ReportExportService(new ObjectMapper());

    @Test
    void csvIncludesRawMetricColumnsAndTransitions() {
        UUID evalId = UUID.randomUUID();
        EvaluationDtos.EvaluationReportResponse report = new EvaluationDtos.EvaluationReportResponse(
                new EvaluationDtos.EvaluationResponse(
                        evalId, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                        "COMPLETED", 1, null, null, null, false, UUID.randomUUID(),
                        Instant.parse("2026-11-09T12:00:00Z"), Instant.parse("2026-11-09T12:05:00Z"),
                        Instant.parse("2026-11-09T12:00:01Z"), Instant.parse("2026-11-09T12:05:00Z")
                ),
                new EvaluationDtos.ScoreResponse(
                        UUID.randomUUID(),
                        "VALID",
                        new BigDecimal("78.5000"),
                        "default-v1",
                        Map.of("F2P", 3, "P2P", 40, "P2F", 0, "F2F", 1),
                        Map.of(),
                        List.of(),
                        List.of(new EvaluationDtos.ScoreMetricResponse(
                                "target_failure_resolution",
                                new BigDecimal("0.9"),
                                new BigDecimal("0.30"),
                                new BigDecimal("27.0"),
                                Map.of()
                        )),
                        Instant.parse("2026-11-09T12:05:00Z")
                ),
                Map.of("F2P", 3, "P2P", 40, "P2F", 0, "F2F", 1),
                new EvaluationDtos.HiddenSummary(2, 2, 1.0, true),
                List.of(),
                List.of()
        );

        String csv = service.toCsv(report);
        assertTrue(csv.contains("section,key,value"));
        assertTrue(csv.contains("metric.target_failure_resolution,raw,0.9"));
        assertTrue(csv.contains("visibleTransition,F2P,3"));
        assertTrue(csv.contains("hidden,neverLabeledF2P,true"));
        assertTrue(service.toJson(report).contains(evalId.toString()));
    }

    @Test
    void leaderboardCsvEscapesCommas() {
        String csv = service.leaderboardCsv(List.of(
                new AnalyticsDtos.LeaderboardEntry(
                        1,
                        UUID.randomUUID(),
                        "Agent, Inc",
                        "FAKE",
                        UUID.randomUUID(),
                        "task-a",
                        UUID.randomUUID(),
                        new BigDecimal("90.0"),
                        "VALID",
                        Map.of("F2P", 1),
                        Instant.parse("2026-11-09T12:00:00Z"),
                        1000L,
                        42L,
                        new BigDecimal("0.01")
                )
        ));
        assertTrue(csv.startsWith("rank,agentId,agentName"));
        assertTrue(csv.contains("\"Agent, Inc\""));
    }
}
