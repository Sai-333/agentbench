package com.agentbench.execution.quality;

import com.agentbench.agents.infra.SecretRedactor;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Runs Checkstyle + SpotBugs + PMD analysis (XML when present, else builtin heuristics),
 * optionally merges Sonar findings when configured, separates pre-existing vs newly introduced
 * findings, and computes an honest normalized score.
 *
 * <p>Checkstyle / SpotBugs / PMD remain the primary local tools. Sonar is optional and never
 * claimed as the sole scoring authority.
 */
@Service
public class QualityAnalysisService {

    private final ObjectMapper objectMapper;
    private final SonarQualityAnalyzer sonarQualityAnalyzer;

    public QualityAnalysisService(ObjectMapper objectMapper, SonarQualityAnalyzer sonarQualityAnalyzer) {
        this.objectMapper = objectMapper;
        this.sonarQualityAnalyzer = sonarQualityAnalyzer == null
                ? SonarQualityAnalyzer.disabled()
                : sonarQualityAnalyzer;
    }

    public AggregatedQuality analyze(Path baselineWorkspace, Path patchedWorkspace) {
        List<QualityToolReport> baselineReports = collectReports(baselineWorkspace);
        List<QualityToolReport> postReports = collectReports(patchedWorkspace);

        Map<String, Set<String>> baselineByTool = fingerprintsByTool(baselineReports);
        Map<String, List<QualityFinding>> newByTool = new LinkedHashMap<>();
        Map<String, List<QualityFinding>> preExistingByTool = new LinkedHashMap<>();
        Map<String, List<QualityFinding>> allPostByTool = new LinkedHashMap<>();

        QualityToolReport sonarPost = null;
        boolean anyReal = false;
        boolean sonarReal = false;
        for (QualityToolReport report : postReports) {
            if (SonarIssuesParser.TOOL.equals(report.tool())) {
                sonarPost = report;
                if (report.stub()) {
                    // Disabled / unconfigured Sonar stays out of scoring aggregates.
                    continue;
                }
                sonarReal = true;
            }
            if (!report.stub()) {
                anyReal = true;
            }
            Set<String> baselineFp = baselineByTool.getOrDefault(report.tool(), Set.of());
            List<QualityFinding> newly = new ArrayList<>();
            List<QualityFinding> preexisting = new ArrayList<>();
            for (QualityFinding finding : report.findings()) {
                if (baselineFp.contains(finding.fingerprint())) {
                    preexisting.add(finding);
                } else {
                    newly.add(finding);
                }
            }
            newByTool.put(report.tool(), newly);
            preExistingByTool.put(report.tool(), preexisting);
            allPostByTool.put(report.tool(), report.findings());
        }

        if (sonarPost == null) {
            sonarPost = QualityToolReport.stub(SonarIssuesParser.TOOL, "sonar_missing");
        }

        int newCount = newByTool.values().stream().mapToInt(List::size).sum();
        int preCount = preExistingByTool.values().stream().mapToInt(List::size).sum();
        int postCount = allPostByTool.values().stream().mapToInt(List::size).sum();
        int baselineCount = baselineReports.stream()
                .filter(r -> !SonarIssuesParser.TOOL.equals(r.tool()) || !r.stub())
                .mapToInt(r -> r.findings().size())
                .sum();

        BigDecimal normalized = null;
        if (anyReal) {
            // Honest: penalize newly introduced findings; perfect if zero new issues.
            // raw = 1 / (1 + newFindings) clipped — never invent a high score without evidence.
            double raw = 1.0 / (1.0 + newCount);
            // Mild additional penalty if post total exploded vs baseline.
            if (baselineCount > 0 && postCount > baselineCount * 2L) {
                raw = raw * 0.75;
            }
            normalized = BigDecimal.valueOf(raw).setScale(4, RoundingMode.HALF_UP);
        }

        ObjectNode metrics = objectMapper.createObjectNode();
        metrics.put("stub", !anyReal);
        metrics.put("schema", "agentbench.quality.v1");
        metrics.put("newFindingCount", newCount);
        metrics.put("preExistingFindingCount", preCount);
        metrics.put("postFindingCount", postCount);
        metrics.put("baselineFindingCount", baselineCount);
        metrics.put("sonarEnabled", sonarQualityAnalyzer.isEnabled());
        metrics.put("sonarContributed", sonarReal);
        if (normalized != null) {
            metrics.put("normalizedScore", normalized);
        } else {
            metrics.putNull("normalizedScore");
            metrics.put("reason", "no_real_tool_results");
        }
        metrics.set("tools", toToolArray(postReports, newByTool, preExistingByTool));
        metrics.set("sonar", toolNode(
                sonarPost,
                newByTool.getOrDefault(SonarIssuesParser.TOOL, List.of()),
                preExistingByTool.getOrDefault(SonarIssuesParser.TOOL, List.of())));

        String toolLabel = sonarReal
                ? "checkstyle+spotbugs+pmd+sonar"
                : "checkstyle+spotbugs+pmd";

        return new AggregatedQuality(
                toolLabel,
                write(metrics),
                normalized,
                anyReal,
                newCount,
                preCount
        );
    }

    /**
     * Prefer XML reports under conventional paths; otherwise run builtin heuristics.
     * Sonar is optional (report import or API) and disabled by default.
     */
    public List<QualityToolReport> collectReports(Path workspace) {
        List<QualityToolReport> reports = new ArrayList<>();
        if (workspace == null || !Files.isDirectory(workspace)) {
            reports.add(QualityToolReport.stub("checkstyle", "workspace_missing"));
            reports.add(QualityToolReport.stub("pmd", "workspace_missing"));
            reports.add(QualityToolReport.stub("spotbugs", "workspace_missing"));
            reports.add(sonarQualityAnalyzer.analyze(workspace));
            return reports;
        }

        String checkstyleXml = readIfPresent(workspace.resolve("target/checkstyle-result.xml"));
        String pmdXml = readIfPresent(workspace.resolve("target/pmd.xml"));
        if (pmdXml == null) {
            pmdXml = readIfPresent(workspace.resolve("target/pmd/pmd.xml"));
        }
        String spotbugsXml = readIfPresent(workspace.resolve("target/spotbugsXml.xml"));
        if (spotbugsXml == null) {
            spotbugsXml = readIfPresent(workspace.resolve("target/spotbugs/spotbugs.xml"));
        }

        boolean usedXml = false;
        if (checkstyleXml != null) {
            reports.add(CheckstyleReportParser.parse(checkstyleXml));
            usedXml = true;
        }
        if (pmdXml != null) {
            reports.add(PmdReportParser.parse(pmdXml));
            usedXml = true;
        }
        if (spotbugsXml != null) {
            reports.add(SpotBugsReportParser.parse(spotbugsXml));
            usedXml = true;
        }

        if (!usedXml) {
            reports.addAll(BuiltinJavaQualityScanner.scan(workspace));
        } else {
            // Fill missing tools with stubs so the report always names all three.
            Set<String> present = new HashSet<>();
            reports.forEach(r -> present.add(r.tool()));
            if (!present.contains("checkstyle")) {
                reports.add(QualityToolReport.stub("checkstyle", "xml_missing"));
            }
            if (!present.contains("pmd")) {
                reports.add(QualityToolReport.stub("pmd", "xml_missing"));
            }
            if (!present.contains("spotbugs")) {
                reports.add(QualityToolReport.stub("spotbugs", "xml_missing"));
            }
        }
        reports.add(sonarQualityAnalyzer.analyze(workspace));
        return reports;
    }

    private ArrayNode toToolArray(
            List<QualityToolReport> postReports,
            Map<String, List<QualityFinding>> newByTool,
            Map<String, List<QualityFinding>> preExistingByTool) {
        ArrayNode array = objectMapper.createArrayNode();
        for (QualityToolReport report : postReports) {
            if (SonarIssuesParser.TOOL.equals(report.tool())) {
                continue;
            }
            array.add(toolNode(
                    report,
                    newByTool.getOrDefault(report.tool(), List.of()),
                    preExistingByTool.getOrDefault(report.tool(), List.of())));
        }
        return array;
    }

    private ObjectNode toolNode(
            QualityToolReport report,
            List<QualityFinding> newly,
            List<QualityFinding> preexisting) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("tool", report.tool());
        node.put("stub", report.stub());
        node.put("notes", report.notes());
        node.put("totalFindings", report.findings().size());
        node.put("newFindings", newly.size());
        node.put("preExistingFindings", preexisting.size());
        ArrayNode samples = objectMapper.createArrayNode();
        int limit = Math.min(20, newly.size());
        for (int i = 0; i < limit; i++) {
            QualityFinding f = newly.get(i);
            ObjectNode sample = objectMapper.createObjectNode();
            sample.put("ruleId", f.ruleId());
            sample.put("severity", f.severity());
            sample.put("filePath", f.filePath());
            sample.put("line", f.line());
            sample.put("message", SecretRedactor.redact(f.message()));
            sample.put("introduced", true);
            samples.add(sample);
        }
        int preLimit = Math.min(5, preexisting.size());
        for (int i = 0; i < preLimit; i++) {
            QualityFinding f = preexisting.get(i);
            ObjectNode sample = objectMapper.createObjectNode();
            sample.put("ruleId", f.ruleId());
            sample.put("severity", f.severity());
            sample.put("filePath", f.filePath());
            sample.put("line", f.line());
            sample.put("message", SecretRedactor.redact(f.message()));
            sample.put("introduced", false);
            samples.add(sample);
        }
        node.set("findingsSample", samples);
        return node;
    }

    private static Map<String, Set<String>> fingerprintsByTool(List<QualityToolReport> reports) {
        Map<String, Set<String>> map = new LinkedHashMap<>();
        for (QualityToolReport report : reports) {
            Set<String> set = new HashSet<>();
            for (QualityFinding finding : report.findings()) {
                set.add(finding.fingerprint());
            }
            map.put(report.tool(), set);
        }
        return map;
    }

    private static String readIfPresent(Path path) {
        try {
            if (path != null && Files.isRegularFile(path)) {
                return Files.readString(path, StandardCharsets.UTF_8);
            }
        } catch (Exception ignored) {
            return null;
        }
        return null;
    }

    private String write(ObjectNode node) {
        try {
            return objectMapper.writeValueAsString(node);
        } catch (Exception ex) {
            return "{\"stub\":true,\"reason\":\"serialize_error\"}";
        }
    }

    public record AggregatedQuality(
            String toolLabel,
            String metricsJson,
            BigDecimal normalizedScore,
            boolean realResults,
            int newFindingCount,
            int preExistingFindingCount
    ) {
    }
}
