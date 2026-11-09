package com.agentbench.analytics.service;

import com.agentbench.analytics.api.dto.AnalyticsDtos;
import com.agentbench.evaluation.api.dto.EvaluationDtos;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

/**
 * Builds downloadable evaluation reports (JSON / CSV). Practical PDF-lite is deferred —
 * JSON and CSV cover automation and spreadsheet use without a heavy PDF stack.
 */
@Service
public class ReportExportService {

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
                }
            }
        }

        return sb.toString();
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
