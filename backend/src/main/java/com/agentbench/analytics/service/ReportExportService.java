package com.agentbench.analytics.service;

import com.agentbench.analytics.api.dto.AnalyticsDtos;
import com.agentbench.evaluation.api.dto.EvaluationDtos;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.lowagie.text.Document;
import com.lowagie.text.DocumentException;
import com.lowagie.text.Font;
import com.lowagie.text.FontFactory;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

/**
 * Builds downloadable evaluation and leaderboard reports (JSON / CSV / PDF).
 * PDF uses OpenPDF (LGPL) — lean, Boot 3–compatible, no binary goldens required in tests.
 */
@Service
public class ReportExportService {

    private static final Font TITLE = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 16);
    private static final Font HEADING = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 12);
    private static final Font BODY = FontFactory.getFont(FontFactory.HELVETICA, 10);
    private static final Font SMALL = FontFactory.getFont(FontFactory.HELVETICA, 9);

    private final ObjectMapper objectMapper;

    public ReportExportService(ObjectMapper objectMapper) {
        // Copy so Instant/LocalDate serialize as ISO-8601 even if a bare mapper is injected in tests.
        this.objectMapper = objectMapper.copy()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    public String toJson(EvaluationDtos.EvaluationReportResponse report) {
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(report);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to serialize evaluation report", ex);
        }
    }

    public String toCsv(EvaluationDtos.EvaluationReportResponse report) {
        StringBuilder sb = new StringBuilder();
        sb.append("section,key,value\n");
        var evaluation = report.evaluation();
        row(sb, "evaluation", "id", evaluation.id());
        row(sb, "evaluation", "state", evaluation.state());
        row(sb, "evaluation", "orgId", evaluation.orgId());
        row(sb, "evaluation", "benchmarkVersionId", evaluation.benchmarkVersionId());
        row(sb, "evaluation", "taskId", evaluation.taskId());
        row(sb, "evaluation", "agentId", evaluation.agentId());
        row(sb, "evaluation", "createdAt", evaluation.createdAt());
        row(sb, "evaluation", "completedAt", evaluation.completedAt());
        row(sb, "evaluation", "failureClass", evaluation.failureClass());
        row(sb, "evaluation", "errorCode", evaluation.errorCode());

        if (report.score() != null) {
            var score = report.score();
            row(sb, "score", "status", score.scoreStatus());
            row(sb, "score", "profileKey", score.profileKey());
            row(sb, "score", "weightedTotal", score.weightedTotal());
            row(sb, "score", "computedAt", score.computedAt());
            if (score.transitionSummary() != null) {
                for (Map.Entry<String, Integer> entry : score.transitionSummary().entrySet()) {
                    row(sb, "transition", entry.getKey(), entry.getValue());
                }
            }
            if (score.metrics() != null) {
                for (EvaluationDtos.ScoreMetricResponse metric : score.metrics()) {
                    row(sb, "metric." + metric.metricKey(), "raw", metric.rawValue());
                    row(sb, "metric." + metric.metricKey(), "weight", metric.weight());
                    row(sb, "metric." + metric.metricKey(), "weighted", metric.weightedValue());
                }
            }
            if (score.warnings() != null) {
                for (int i = 0; i < score.warnings().size(); i++) {
                    row(sb, "warning", String.valueOf(i), score.warnings().get(i));
                }
            }
        }

        if (report.visibleTransitions() != null) {
            for (Map.Entry<String, Integer> entry : report.visibleTransitions().entrySet()) {
                row(sb, "visibleTransition", entry.getKey(), entry.getValue());
            }
        }

        if (report.hidden() != null) {
            row(sb, "hidden", "total", report.hidden().total());
            row(sb, "hidden", "passed", report.hidden().passed());
            row(sb, "hidden", "passRate", report.hidden().passRate());
            row(sb, "hidden", "neverLabeledF2P", report.hidden().neverLabeledF2P());
        }

        if (report.testRuns() != null) {
            for (EvaluationDtos.TestRunResponse run : report.testRuns()) {
                for (EvaluationDtos.TestCaseResultResponse result : run.results()) {
                    String prefix = "case." + run.phase() + "." + result.caseKey();
                    row(sb, prefix, "status", result.status());
                    row(sb, prefix, "transition", result.transition());
                    row(sb, prefix, "durationMs", result.durationMs());
                    row(sb, prefix, "category", result.category());
                    row(sb, prefix, "flaky", result.flaky());
                    row(sb, prefix, "quarantined", result.quarantined());
                }
            }
        }

        return sb.toString();
    }

    public byte[] toPdf(EvaluationDtos.EvaluationReportResponse report) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            Document document = new Document(PageSize.A4, 48, 48, 48, 48);
            PdfWriter.getInstance(document, out);
            document.open();

            document.add(new Paragraph("AgentBench Evaluation Report", TITLE));
            document.add(spacer(8));

            var evaluation = report.evaluation();
            document.add(new Paragraph("Evaluation", HEADING));
            document.add(line("Evaluation ID: " + evaluation.id()));
            document.add(line("State: " + evaluation.state()));
            document.add(line("Org ID: " + evaluation.orgId()));
            document.add(line("Benchmark version: " + evaluation.benchmarkVersionId()));
            document.add(line("Task ID: " + evaluation.taskId()));
            document.add(line("Agent ID: " + evaluation.agentId()));
            document.add(line("Created: " + stringOrDash(evaluation.createdAt())));
            document.add(line("Completed: " + stringOrDash(evaluation.completedAt())));
            if (evaluation.failureClass() != null) {
                document.add(line("Failure class: " + evaluation.failureClass()));
            }
            if (evaluation.errorCode() != null) {
                document.add(line("Error code: " + evaluation.errorCode()));
            }
            document.add(spacer(10));

            if (report.score() != null) {
                var score = report.score();
                document.add(new Paragraph("Score", HEADING));
                document.add(line("Status: " + score.scoreStatus()));
                document.add(line("Profile: " + score.profileKey()));
                document.add(line("Weighted total: " + score.weightedTotal()));
                document.add(line("Computed: " + stringOrDash(score.computedAt())));
                document.add(spacer(6));

                if (score.metrics() != null && !score.metrics().isEmpty()) {
                    document.add(new Paragraph("Metrics (raw / weight / weighted)", HEADING));
                    PdfPTable metrics = new PdfPTable(new float[]{3f, 1.2f, 1.2f, 1.2f});
                    metrics.setWidthPercentage(100);
                    headerCell(metrics, "Metric");
                    headerCell(metrics, "Raw");
                    headerCell(metrics, "Weight");
                    headerCell(metrics, "Weighted");
                    for (EvaluationDtos.ScoreMetricResponse metric : score.metrics()) {
                        bodyCell(metrics, metric.metricKey());
                        bodyCell(metrics, stringOrDash(metric.rawValue()));
                        bodyCell(metrics, stringOrDash(metric.weight()));
                        bodyCell(metrics, stringOrDash(metric.weightedValue()));
                    }
                    document.add(metrics);
                    document.add(spacer(8));
                }

                if (score.transitionSummary() != null && !score.transitionSummary().isEmpty()) {
                    document.add(new Paragraph("Transition summary", HEADING));
                    for (Map.Entry<String, Integer> entry : score.transitionSummary().entrySet()) {
                        document.add(line(entry.getKey() + ": " + entry.getValue()));
                    }
                    document.add(spacer(6));
                }

                if (score.warnings() != null && !score.warnings().isEmpty()) {
                    document.add(new Paragraph("Warnings", HEADING));
                    for (String warning : score.warnings()) {
                        document.add(line("- " + warning));
                    }
                    document.add(spacer(6));
                }
            }

            if (report.visibleTransitions() != null && !report.visibleTransitions().isEmpty()) {
                document.add(new Paragraph("Visible transitions (F2P / P2P / P2F / F2F)", HEADING));
                for (Map.Entry<String, Integer> entry : report.visibleTransitions().entrySet()) {
                    document.add(line(entry.getKey() + ": " + entry.getValue()));
                }
                document.add(spacer(6));
            }

            if (report.hidden() != null) {
                document.add(new Paragraph("Hidden suite (post-only pass rate; never labeled F2P)", HEADING));
                document.add(line("Total: " + report.hidden().total()));
                document.add(line("Passed: " + report.hidden().passed()));
                document.add(line("Pass rate: " + stringOrDash(report.hidden().passRate())));
                document.add(line("Never labeled F2P: " + report.hidden().neverLabeledF2P()));
                document.add(spacer(6));
            }

            document.add(new Paragraph(
                    "Raw metrics remain authoritative; weighted total is a convenience rollup.",
                    SMALL));
            document.close();
            return out.toByteArray();
        } catch (DocumentException ex) {
            throw new IllegalStateException("Failed to build evaluation PDF", ex);
        }
    }

    public String leaderboardCsv(List<AnalyticsDtos.LeaderboardEntry> entries) {
        StringJoiner joiner = new StringJoiner("\n");
        joiner.add("rank,agentId,agentName,taskSlug,evaluationId,weightedTotal,completedAt,totalTokens,estimatedCostUsd");
        for (AnalyticsDtos.LeaderboardEntry e : entries) {
            joiner.add(String.join(",",
                    csv(e.rank()),
                    csv(e.agentId()),
                    csv(e.agentName()),
                    csv(e.taskSlug()),
                    csv(e.evaluationId()),
                    csv(e.weightedTotal()),
                    csv(e.completedAt()),
                    csv(e.totalTokens()),
                    csv(e.estimatedCostUsd())
            ));
        }
        return joiner.toString() + "\n";
    }

    public byte[] leaderboardPdf(AnalyticsDtos.LeaderboardResponse board) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            Document document = new Document(PageSize.A4.rotate(), 36, 36, 36, 36);
            PdfWriter.getInstance(document, out);
            document.open();

            document.add(new Paragraph("AgentBench Leaderboard", TITLE));
            document.add(spacer(6));
            document.add(line("Benchmark version: " + board.benchmarkVersionId()));
            document.add(line("Scoring profile: " + stringOrDash(board.scoringProfileKey())));
            if (board.note() != null && !board.note().isBlank()) {
                document.add(line(board.note()));
            }
            document.add(spacer(10));

            PdfPTable table = new PdfPTable(new float[]{0.7f, 2.2f, 1.6f, 1.2f, 1.4f, 1.6f});
            table.setWidthPercentage(100);
            headerCell(table, "Rank");
            headerCell(table, "Agent");
            headerCell(table, "Task");
            headerCell(table, "Score");
            headerCell(table, "Status");
            headerCell(table, "Completed");

            List<AnalyticsDtos.LeaderboardEntry> entries =
                    board.entries() == null ? List.of() : board.entries();
            if (entries.isEmpty()) {
                PdfPCell empty = new PdfPCell(new Phrase("No leaderboard entries", BODY));
                empty.setColspan(6);
                table.addCell(empty);
            } else {
                for (AnalyticsDtos.LeaderboardEntry entry : entries) {
                    bodyCell(table, String.valueOf(entry.rank()));
                    bodyCell(table, stringOrDash(entry.agentName()));
                    bodyCell(table, stringOrDash(entry.taskSlug()));
                    bodyCell(table, stringOrDash(entry.weightedTotal()));
                    bodyCell(table, stringOrDash(entry.scoreStatus()));
                    bodyCell(table, stringOrDash(entry.completedAt()));
                }
            }
            document.add(table);
            document.add(spacer(8));
            document.add(new Paragraph(
                    "Organization-private export. Same benchmark version; best score per agent×task.",
                    SMALL));
            document.close();
            return out.toByteArray();
        } catch (DocumentException ex) {
            throw new IllegalStateException("Failed to build leaderboard PDF", ex);
        }
    }

    public Map<String, Object> flatSummary(EvaluationDtos.EvaluationReportResponse report) {
        Map<String, Object> flat = new LinkedHashMap<>();
        flat.put("evaluationId", report.evaluation().id());
        flat.put("state", report.evaluation().state());
        flat.put("agentId", report.evaluation().agentId());
        flat.put("taskId", report.evaluation().taskId());
        flat.put("benchmarkVersionId", report.evaluation().benchmarkVersionId());
        if (report.score() != null) {
            flat.put("weightedTotal", report.score().weightedTotal());
            flat.put("scoreStatus", report.score().scoreStatus());
            flat.put("profileKey", report.score().profileKey());
            List<Map<String, Object>> metrics = new ArrayList<>();
            if (report.score().metrics() != null) {
                for (EvaluationDtos.ScoreMetricResponse metric : report.score().metrics()) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("key", metric.metricKey());
                    m.put("raw", metric.rawValue());
                    m.put("weight", metric.weight());
                    m.put("weighted", metric.weightedValue());
                    metrics.add(m);
                }
            }
            flat.put("metrics", metrics);
        }
        flat.put("visibleTransitions", report.visibleTransitions());
        flat.put("hidden", report.hidden());
        return flat;
    }

    private static Paragraph line(String text) {
        return new Paragraph(text, BODY);
    }

    private static Paragraph spacer(float points) {
        Paragraph p = new Paragraph(" ");
        p.setSpacingAfter(points);
        return p;
    }

    private static void headerCell(PdfPTable table, String text) {
        PdfPCell cell = new PdfPCell(new Phrase(text, HEADING));
        table.addCell(cell);
    }

    private static void bodyCell(PdfPTable table, String text) {
        table.addCell(new PdfPCell(new Phrase(text, SMALL)));
    }

    private static String stringOrDash(Object value) {
        return value == null ? "—" : String.valueOf(value);
    }

    private static void row(StringBuilder sb, String section, String key, Object value) {
        sb.append(csv(section)).append(',')
                .append(csv(key)).append(',')
                .append(csv(value)).append('\n');
    }

    static String csv(Object value) {
        if (value == null) {
            return "";
        }
        String text = String.valueOf(value);
        if (text.contains(",") || text.contains("\"") || text.contains("\n") || text.contains("\r")) {
            return "\"" + text.replace("\"", "\"\"") + "\"";
        }
        return text;
    }
}
