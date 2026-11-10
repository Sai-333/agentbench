package com.agentbench.analytics;

import com.agentbench.analytics.api.dto.AnalyticsDtos;
import com.agentbench.analytics.service.ReportExportService;
import com.agentbench.evaluation.api.dto.EvaluationDtos;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.parser.PdfTextExtractor;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReportExportServiceTest {

    private final ReportExportService service = new ReportExportService(new ObjectMapper());

    @Test
    void csvIncludesRawMetricColumnsAndTransitions() {
        UUID evalId = UUID.randomUUID();
        EvaluationDtos.EvaluationReportResponse report = sampleReport(evalId);

        String csv = service.toCsv(report);
        assertTrue(csv.contains("section,key,value"));
        assertTrue(csv.contains("metric.target_failure_resolution,raw,0.9"));
        assertTrue(csv.contains("visibleTransition,F2P,3"));
        assertTrue(csv.contains("hidden,neverLabeledF2P,true"));
        assertTrue(service.toJson(report).contains(evalId.toString()));
    }

    @Test
    void evaluationPdfHasMagicBytesPageAndKeyText() throws IOException {
        UUID evalId = UUID.randomUUID();
        byte[] pdf = service.toPdf(sampleReport(evalId));

        assertTrue(pdf.length > 100);
        assertEquals("%PDF", new String(pdf, 0, 4, StandardCharsets.US_ASCII));

        try (PdfReader reader = new PdfReader(pdf)) {
            assertEquals(1, reader.getNumberOfPages());
            String text = new PdfTextExtractor(reader).getTextFromPage(1);
            assertTrue(text.contains("AgentBench Evaluation Report"));
            assertTrue(text.contains(evalId.toString()));
            assertTrue(text.contains("target_failure_resolution"));
            assertTrue(text.contains("Never labeled F2P"));
            assertTrue(text.contains("Weighted total"));
        }
    }

    @Test
    void leaderboardCsvEscapesCommas() {
        String csv = service.leaderboardCsv(List.of(sampleLeaderboardEntry("Agent, Inc")));
        assertTrue(csv.startsWith("rank,agentId,agentName"));
        assertTrue(csv.contains("\"Agent, Inc\""));
    }

    @Test
    void leaderboardPdfHasMagicBytesPageAndKeyText() throws IOException {
        UUID versionId = UUID.randomUUID();
        AnalyticsDtos.LeaderboardResponse board = new AnalyticsDtos.LeaderboardResponse(
                versionId,
                "default-v1",
                "Organization-private leaderboard.",
                List.of(sampleLeaderboardEntry("agent-alpha")),
                new AnalyticsDtos.PageMeta(0, 25, 1)
        );

        byte[] pdf = service.leaderboardPdf(board);
        assertEquals("%PDF", new String(pdf, 0, 4, StandardCharsets.US_ASCII));

        try (PdfReader reader = new PdfReader(pdf)) {
            assertEquals(1, reader.getNumberOfPages());
            String text = new PdfTextExtractor(reader).getTextFromPage(1);
            assertTrue(text.contains("AgentBench Leaderboard"));
            assertTrue(text.contains(versionId.toString()));
            assertTrue(text.contains("agent-alpha"));
            assertTrue(text.contains("default-v1"));
        }
    }

    private static EvaluationDtos.EvaluationReportResponse sampleReport(UUID evalId) {
        return new EvaluationDtos.EvaluationReportResponse(
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
    }

    private static AnalyticsDtos.LeaderboardEntry sampleLeaderboardEntry(String agentName) {
        return new AnalyticsDtos.LeaderboardEntry(
                1,
                UUID.randomUUID(),
                agentName,
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
        );
    }
}
