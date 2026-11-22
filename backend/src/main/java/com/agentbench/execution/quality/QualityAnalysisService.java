package com.agentbench.execution.quality;

import com.agentbench.agents.infra.SecretRedactor;
import com.agentbench.execution.config.ExecutionProperties;
import com.agentbench.execution.service.ExecutionIsolatorService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Autowired;
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
import java.util.UUID;

/**
 * Runs Checkstyle, SpotBugs, and PMD (existing XML, otherwise the tools themselves).
 * When none of those tools run, records {@code builtin_java_heuristics} instead of
 * labeling the heuristic as those tools. Optionally merges Sonar / ruff / eslint.
 * Separates pre-existing vs newly introduced findings and computes a normalized score
 * from the new-finding count. Sonar and the language scanners stay optional.
 */
@Service
public class QualityAnalysisService {

    private static final String HEURISTIC = BuiltinJavaQualityScanner.TOOL;

    private final ObjectMapper objectMapper;
    private final SonarQualityAnalyzer sonarQualityAnalyzer;
    private final PythonQualityAnalyzer pythonQualityAnalyzer;
    private final NodeQualityAnalyzer nodeQualityAnalyzer;
    private final JavaQualityToolchain javaQualityToolchain;

    public QualityAnalysisService(
            ObjectMapper objectMapper,
            SonarQualityAnalyzer sonarQualityAnalyzer,
            PythonQualityAnalyzer pythonQualityAnalyzer,
            NodeQualityAnalyzer nodeQualityAnalyzer) {
        this(objectMapper, sonarQualityAnalyzer, pythonQualityAnalyzer, nodeQualityAnalyzer,
                JavaQualityToolchain.inProcess());
    }

    public QualityAnalysisService(
            ObjectMapper objectMapper,
            SonarQualityAnalyzer sonarQualityAnalyzer,
            PythonQualityAnalyzer pythonQualityAnalyzer,
            NodeQualityAnalyzer nodeQualityAnalyzer,
            JavaQualityToolchain javaQualityToolchain) {
        this.objectMapper = objectMapper;
        this.sonarQualityAnalyzer = sonarQualityAnalyzer == null
                ? SonarQualityAnalyzer.disabled()
                : sonarQualityAnalyzer;
        this.pythonQualityAnalyzer = pythonQualityAnalyzer == null
                ? PythonQualityAnalyzer.disabled()
                : pythonQualityAnalyzer;
        this.nodeQualityAnalyzer = nodeQualityAnalyzer == null
                ? NodeQualityAnalyzer.disabled()
                : nodeQualityAnalyzer;
        this.javaQualityToolchain = javaQualityToolchain == null
                ? JavaQualityToolchain.inProcess()
                : javaQualityToolchain;
    }

    @Autowired
    public QualityAnalysisService(
            ObjectMapper objectMapper,
            SonarQualityAnalyzer sonarQualityAnalyzer,
            PythonQualityAnalyzer pythonQualityAnalyzer,
            NodeQualityAnalyzer nodeQualityAnalyzer,
            ExecutionIsolatorService isolatorService,
            ExecutionProperties executionProperties) {
        this(objectMapper, sonarQualityAnalyzer, pythonQualityAnalyzer, nodeQualityAnalyzer,
                JavaQualityToolchain.withIsolator(isolatorService, executionProperties));
    }

    /** Convenience for unit tests / callers without an evaluation id (no isolator invoke). */
    public AggregatedQuality analyze(Path baselineWorkspace, Path patchedWorkspace) {
        return analyze(null, baselineWorkspace, patchedWorkspace);
    }

    public AggregatedQuality analyze(UUID evaluationId, Path baselineWorkspace, Path patchedWorkspace) {
        List<QualityToolReport> baselineReports = collectReports(evaluationId, baselineWorkspace);
        List<QualityToolReport> postReports = collectReports(evaluationId, patchedWorkspace);

        Map<String, Set<String>> baselineByTool = fingerprintsByTool(baselineReports);
        Map<String, List<QualityFinding>> newByTool = new LinkedHashMap<>();
        Map<String, List<QualityFinding>> preExistingByTool = new LinkedHashMap<>();
        Map<String, List<QualityFinding>> allPostByTool = new LinkedHashMap<>();

        QualityToolReport sonarPost = null;
        QualityToolReport ruffPost = null;
        QualityToolReport eslintPost = null;
        boolean anyReal = false;
        boolean sonarReal = false;
        boolean ruffReal = false;
        boolean eslintReal = false;
        for (QualityToolReport report : postReports) {
            if (SonarIssuesParser.TOOL.equals(report.tool())) {
                sonarPost = report;
                if (report.stub()) {
                    // Disabled / unconfigured Sonar stays out of scoring aggregates.
                    continue;
                }
                sonarReal = true;
            }
            if (RuffReportParser.TOOL.equals(report.tool())) {
                ruffPost = report;
                if (!report.stub()) {
                    ruffReal = true;
                }
            }
            if (EslintReportParser.TOOL.equals(report.tool())) {
                eslintPost = report;
                if (!report.stub()) {
                    eslintReal = true;
                }
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
        if (ruffPost == null) {
            ruffPost = QualityToolReport.stub(RuffReportParser.TOOL, "ruff_missing");
        }
        if (eslintPost == null) {
            eslintPost = QualityToolReport.stub(EslintReportParser.TOOL, "eslint_missing");
        }

        int newCount = newByTool.values().stream().mapToInt(List::size).sum();
        int preCount = preExistingByTool.values().stream().mapToInt(List::size).sum();
        int postCount = allPostByTool.values().stream().mapToInt(List::size).sum();
        int baselineCount = baselineReports.stream()
                .filter(r -> !isOptionalStubExcluded(r))
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
        metrics.put("pythonEnabled", pythonQualityAnalyzer.isEnabled());
        metrics.put("pythonContributed", ruffReal);
        metrics.put("nodeEnabled", nodeQualityAnalyzer.isEnabled());
        metrics.put("nodeContributed", eslintReal);
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
        metrics.set("ruff", toolNode(
                ruffPost,
                newByTool.getOrDefault(RuffReportParser.TOOL, List.of()),
                preExistingByTool.getOrDefault(RuffReportParser.TOOL, List.of())));
        metrics.set("eslint", toolNode(
                eslintPost,
                newByTool.getOrDefault(EslintReportParser.TOOL, List.of()),
                preExistingByTool.getOrDefault(EslintReportParser.TOOL, List.of())));

        boolean javaReal = postReports.stream().anyMatch(QualityAnalysisService::isRealPrimaryTool);
        boolean heuristicReal = postReports.stream().anyMatch(r -> HEURISTIC.equals(r.tool()) && !r.stub());
        String toolLabel = buildToolLabel(javaReal, heuristicReal, sonarReal, ruffReal, eslintReal);

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
     * Read a tool's XML when the tree already has it. Otherwise run that tool.
     * When none of the three run, append {@code builtin_java_heuristics}.
     * Sonar / Python / Node stay optional and disabled by default.
     */
    public List<QualityToolReport> collectReports(Path workspace) {
        return collectReports(null, workspace);
    }

    public List<QualityToolReport> collectReports(UUID evaluationId, Path workspace) {
        List<QualityToolReport> reports = new ArrayList<>();
        if (workspace == null || !Files.isDirectory(workspace)) {
            reports.add(QualityToolReport.stub("checkstyle", JavaQualityToolchain.DID_NOT_RUN));
            reports.add(QualityToolReport.stub("pmd", JavaQualityToolchain.DID_NOT_RUN));
            reports.add(QualityToolReport.stub("spotbugs", JavaQualityToolchain.DID_NOT_RUN));
            reports.add(sonarQualityAnalyzer.analyze(workspace));
            reports.add(pythonQualityAnalyzer.analyze(evaluationId, workspace));
            reports.add(nodeQualityAnalyzer.analyze(evaluationId, workspace));
            return reports;
        }

        Map<String, QualityToolReport> fromXml = xmlReports(workspace);
        List<QualityToolReport> engines = javaQualityToolchain.scan(evaluationId, workspace);
        Map<String, QualityToolReport> fromEngine = new LinkedHashMap<>();
        for (QualityToolReport report : engines) {
            fromEngine.put(report.tool(), report);
        }
        for (String tool : List.of("checkstyle", "pmd", "spotbugs")) {
            QualityToolReport xml = fromXml.get(tool);
            QualityToolReport engine = fromEngine.get(tool);
            if (xml != null && !xml.stub()) {
                reports.add(xml);
            } else if (engine != null) {
                reports.add(engine);
            } else {
                reports.add(QualityToolReport.stub(tool, JavaQualityToolchain.DID_NOT_RUN));
            }
        }
        boolean anyJavaReal = reports.stream().anyMatch(QualityAnalysisService::isRealPrimaryTool);
        if (!anyJavaReal) {
            reports.addAll(BuiltinJavaQualityScanner.scan(workspace));
        }
        reports.add(sonarQualityAnalyzer.analyze(workspace));
        reports.add(pythonQualityAnalyzer.analyze(evaluationId, workspace));
        reports.add(nodeQualityAnalyzer.analyze(evaluationId, workspace));
        return reports;
    }

    private static Map<String, QualityToolReport> xmlReports(Path workspace) {
        Map<String, QualityToolReport> found = new LinkedHashMap<>();
        QualityToolReport checkstyle = CheckstyleReportParser.parse(
                readIfPresent(workspace.resolve("target/checkstyle-result.xml")));
        if (!checkstyle.stub()) {
            found.put("checkstyle", checkstyle);
        }
        String pmdXml = readIfPresent(workspace.resolve("target/pmd.xml"));
        if (pmdXml == null) {
            pmdXml = readIfPresent(workspace.resolve("target/pmd/pmd.xml"));
        }
        QualityToolReport pmd = PmdReportParser.parse(pmdXml);
        if (!pmd.stub()) {
            found.put("pmd", pmd);
        }
        String spotbugsXml = readIfPresent(workspace.resolve("target/spotbugsXml.xml"));
        if (spotbugsXml == null) {
            spotbugsXml = readIfPresent(workspace.resolve("target/spotbugs/spotbugs.xml"));
        }
        QualityToolReport spotbugs = SpotBugsReportParser.parse(spotbugsXml);
        if (!spotbugs.stub()) {
            found.put("spotbugs", spotbugs);
        }
        return found;
    }

    private static boolean isRealPrimaryTool(QualityToolReport report) {
        if (report == null || report.stub()) {
            return false;
        }
        return "checkstyle".equals(report.tool())
                || "pmd".equals(report.tool())
                || "spotbugs".equals(report.tool());
    }

    private static boolean isOptionalStubExcluded(QualityToolReport report) {
        if (report == null || !report.stub()) {
            return false;
        }
        return SonarIssuesParser.TOOL.equals(report.tool())
                || RuffReportParser.TOOL.equals(report.tool())
                || EslintReportParser.TOOL.equals(report.tool());
    }

    private static String buildToolLabel(
            boolean javaReal,
            boolean heuristicReal,
            boolean sonarReal,
            boolean ruffReal,
            boolean eslintReal) {
        StringBuilder label = new StringBuilder(javaReal
                ? "checkstyle+spotbugs+pmd"
                : (heuristicReal ? HEURISTIC : "unavailable"));
        if (sonarReal) {
            label.append("+sonar");
        }
        if (ruffReal) {
            label.append("+ruff");
        }
        if (eslintReal) {
            label.append("+eslint");
        }
        return label.toString();
    }

    private ArrayNode toToolArray(
            List<QualityToolReport> postReports,
            Map<String, List<QualityFinding>> newByTool,
            Map<String, List<QualityFinding>> preExistingByTool) {
        ArrayNode array = objectMapper.createArrayNode();
        for (QualityToolReport report : postReports) {
            if (SonarIssuesParser.TOOL.equals(report.tool())) {
                continue; // dedicated metrics.sonar node
            }
            // ruff/eslint stay in tools[] and also have dedicated nodes.
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
        int limit = Math.min(80, newly.size());
        for (int i = 0; i < limit; i++) {
            QualityFinding f = newly.get(i);
            ObjectNode sample = objectMapper.createObjectNode();
            sample.put("tool", f.tool());
            sample.put("ruleId", f.ruleId());
            sample.put("severity", f.severity());
            sample.put("filePath", f.filePath());
            sample.put("line", f.line());
            sample.put("message", SecretRedactor.redact(f.message()));
            sample.put("introduced", true);
            samples.add(sample);
        }
        int preLimit = Math.min(80, preexisting.size());
        for (int i = 0; i < preLimit; i++) {
            QualityFinding f = preexisting.get(i);
            ObjectNode sample = objectMapper.createObjectNode();
            sample.put("tool", f.tool());
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
