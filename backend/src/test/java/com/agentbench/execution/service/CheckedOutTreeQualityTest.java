package com.agentbench.execution.service;

import com.agentbench.execution.quality.JavaQualityToolchain;
import com.agentbench.execution.quality.NodeQualityAnalyzer;
import com.agentbench.execution.quality.PythonQualityAnalyzer;
import com.agentbench.execution.quality.QualityAnalysisService;
import com.agentbench.execution.quality.QualityFinding;
import com.agentbench.execution.quality.QualityToolReport;
import com.agentbench.execution.quality.SonarQualityAnalyzer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Quality stage on a tree produced by {@link GitSnapshotCheckout}, using Checkstyle, PMD, and SpotBugs.
 */
class CheckedOutTreeQualityTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final QualityAnalysisService service = new QualityAnalysisService(
            objectMapper,
            SonarQualityAnalyzer.disabled(),
            PythonQualityAnalyzer.disabled(),
            NodeQualityAnalyzer.disabled(),
            JavaQualityToolchain.inProcess());

    @TempDir
    Path temp;

    @Test
    void checkedOutTreeShowsFindingsFromEachTool() throws Exception {
        Path origin = temp.resolve("origin");
        Files.createDirectories(origin.resolve("src"));
        Files.writeString(origin.resolve("src/Sample.java"), baselineSource(), StandardCharsets.UTF_8);
        git(origin, "init", "-q", "-b", "main");
        git(origin, "-c", "user.email=test@example.com", "-c", "user.name=test", "add", "src/Sample.java");
        git(origin, "-c", "user.email=test@example.com", "-c", "user.name=test", "commit", "-q", "-m", "base");
        String baselineSha = git(origin, "rev-parse", "HEAD").trim();

        Files.writeString(origin.resolve("src/Sample.java"), patchedSource(), StandardCharsets.UTF_8);
        git(origin, "-c", "user.email=test@example.com", "-c", "user.name=test", "add", "src/Sample.java");
        git(origin, "-c", "user.email=test@example.com", "-c", "user.name=test", "commit", "-q", "-m", "bug");
        String patchedSha = git(origin, "rev-parse", "HEAD").trim();

        Path baseline = temp.resolve("baseline");
        Path patched = temp.resolve("patched");
        Files.createDirectories(baseline);
        Files.createDirectories(patched);
        GitSnapshotCheckout checkout = new GitSnapshotCheckout("");
        checkout.materialize(baseline, origin.toAbsolutePath().toString(), baselineSha);
        checkout.materialize(patched, origin.toAbsolutePath().toString(), patchedSha);

        QualityAnalysisService.AggregatedQuality result = service.analyze(baseline, patched);

        assertEquals("checkstyle+spotbugs+pmd", result.toolLabel());
        assertTrue(result.realResults());
        assertTrue(result.newFindingCount() >= 1);
        assertTrue(result.preExistingFindingCount() >= 1);
        JsonNode metrics = objectMapper.readTree(result.metricsJson());
        assertFalse(metrics.path("stub").asBoolean());
        for (String tool : List.of("checkstyle", "pmd", "spotbugs")) {
            JsonNode node = toolNode(metrics, tool);
            assertFalse(node.path("stub").asBoolean(), tool);
            assertFalse(node.path("notes").asText().contains("builtin_java_heuristics"), tool);
            assertTrue(node.path("findingsSample").size() >= 1, tool);
            JsonNode sample = node.path("findingsSample").get(0);
            assertFalse(sample.path("ruleId").asText().isBlank(), tool);
            assertFalse(sample.path("filePath").asText().isBlank(), tool);
            assertFalse(sample.path("message").asText().isBlank(), tool);
            assertTrue(sample.has("introduced"), tool);
        }
        assertTrue(hasIntroduced(metrics, "spotbugs", true));
        assertTrue(metrics.path("tools").toString().contains("src/Sample.java"));
    }

    @Test
    void engineFindingsCarryToolFileRuleAndMessage() throws Exception {
        Path workspace = temp.resolve("tree");
        Files.createDirectories(workspace.resolve("src"));
        Files.writeString(workspace.resolve("src/Sample.java"), patchedSource(), StandardCharsets.UTF_8);

        List<QualityToolReport> reports = JavaQualityToolchain.inProcess().scan(null, workspace);

        assertEquals(3, reports.size());
        for (QualityToolReport report : reports) {
            assertFalse(report.stub(), report.tool());
            assertFalse(report.notes().contains("builtin_java_heuristics"), report.tool());
            assertFalse(report.findings().isEmpty(), report.tool());
            for (QualityFinding finding : report.findings()) {
                assertEquals(report.tool(), finding.tool());
                assertFalse(finding.ruleId().isBlank());
                assertTrue(finding.filePath().endsWith("Sample.java"), finding.filePath());
                assertFalse(finding.message().isBlank());
            }
        }
    }

    private static JsonNode toolNode(JsonNode metrics, String tool) {
        for (JsonNode node : metrics.path("tools")) {
            if (tool.equals(node.path("tool").asText())) {
                return node;
            }
        }
        throw new AssertionError("missing tool " + tool);
    }

    private static boolean hasIntroduced(JsonNode metrics, String tool, boolean introduced) {
        for (JsonNode finding : toolNode(metrics, tool).path("findingsSample")) {
            if (finding.path("introduced").asBoolean() == introduced) {
                return true;
            }
        }
        return false;
    }

    private static String baselineSource() {
        return """
                public class Sample {
                    public String same(String value) {
                        if (value.equals("hello-this-line-is-intentionally-longer-than-one-hundred-characters-for-the-length-check")) {
                            System.out.println("matched");
                        }
                        try {
                        } catch (RuntimeException ex) {
                        }
                        return value;
                    }
                }
                """;
    }

    private static String patchedSource() {
        return """
                public class Sample {
                    public String same(String value) {
                        if (value == "hello-this-line-is-intentionally-longer-than-one-hundred-characters-for-the-length-check") {
                            System.out.println("matched");
                        }
                        try {
                        } catch (RuntimeException ex) {
                        }
                        return value;
                    }
                }
                """;
    }

    private static String git(Path dir, String... args) throws Exception {
        String[] command = new String[args.length + 1];
        command[0] = "git";
        System.arraycopy(args, 0, command, 1, args.length);
        Process process = new ProcessBuilder(command)
                .directory(dir.toFile())
                .redirectErrorStream(true)
                .start();
        byte[] output = process.getInputStream().readAllBytes();
        int exit = process.waitFor();
        String text = new String(output, StandardCharsets.UTF_8);
        if (exit != 0) {
            throw new IllegalStateException(text);
        }
        return text;
    }
}
