package com.agentbench.execution;

import com.agentbench.execution.quality.CheckstyleReportParser;
import com.agentbench.execution.quality.QualityAnalysisService;
import com.agentbench.execution.quality.QualityToolReport;
import com.agentbench.execution.quality.SonarProperties;
import com.agentbench.execution.quality.SonarQualityAnalyzer;
import com.agentbench.execution.quality.SonarQualityClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QualityAnalysisServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final QualityAnalysisService service =
            new QualityAnalysisService(objectMapper, SonarQualityAnalyzer.disabled());

    @TempDir
    Path temp;

    @Test
    void parsesCheckstyleXml() {
        String xml = """
                <?xml version="1.0"?>
                <checkstyle version="10">
                  <file name="src/Main.java">
                    <error line="3" severity="warning" message="System.out" source="RegexpSinglelineJava"/>
                  </file>
                </checkstyle>
                """;
        QualityToolReport report = CheckstyleReportParser.parse(xml);
        assertFalse(report.stub());
        assertEquals(1, report.findings().size());
        assertEquals("checkstyle", report.tool());
    }

    @Test
    void separatesNewVersusPreExistingFindings() throws Exception {
        Path baseline = temp.resolve("baseline");
        Path patched = temp.resolve("patched");
        Files.createDirectories(baseline.resolve("src"));
        Files.createDirectories(patched.resolve("src"));
        Files.writeString(baseline.resolve("src/App.java"), """
                class App {
                  public int x;
                }
                """, StandardCharsets.UTF_8);
        Files.writeString(patched.resolve("src/App.java"), """
                class App {
                  public int x;
                  void run() { System.out.println("hi"); }
                }
                """, StandardCharsets.UTF_8);

        QualityAnalysisService.AggregatedQuality result = service.analyze(baseline, patched);
        assertTrue(result.realResults());
        assertTrue(result.newFindingCount() >= 1);
        assertTrue(result.normalizedScore() != null);
        JsonNode metrics = objectMapper.readTree(result.metricsJson());
        assertFalse(metrics.path("stub").asBoolean());
        assertTrue(metrics.path("tools").isArray());
        assertTrue(metrics.has("sonar"));
        assertTrue(metrics.path("sonar").path("stub").asBoolean());
        assertFalse(metrics.path("sonarEnabled").asBoolean());
        assertFalse(metrics.path("sonarContributed").asBoolean());
        assertEquals("checkstyle+spotbugs+pmd", result.toolLabel());
    }

    @Test
    void prefersXmlReportsWhenPresent() throws Exception {
        Path ws = temp.resolve("xml-ws");
        Files.createDirectories(ws.resolve("target"));
        Files.writeString(ws.resolve("target/checkstyle-result.xml"), """
                <?xml version="1.0"?>
                <checkstyle version="10">
                  <file name="A.java">
                    <error line="1" severity="error" message="boom" source="Rule"/>
                  </file>
                </checkstyle>
                """, StandardCharsets.UTF_8);
        var reports = service.collectReports(ws);
        assertTrue(reports.stream().anyMatch(r -> "checkstyle".equals(r.tool()) && !r.stub() && r.findings().size() == 1));
    }

    @Test
    void sonarImportContributesNewFindingsWithoutReplacingPrimaryTools() throws Exception {
        Path baseline = temp.resolve("b");
        Path patched = temp.resolve("p");
        Files.createDirectories(baseline.resolve("target"));
        Files.createDirectories(patched.resolve("target"));
        Files.createDirectories(baseline.resolve("src"));
        Files.createDirectories(patched.resolve("src"));
        Files.writeString(baseline.resolve("src/App.java"), "class App {}\n", StandardCharsets.UTF_8);
        Files.writeString(patched.resolve("src/App.java"), "class App {}\n", StandardCharsets.UTF_8);

        String shared = """
                {"issues":[{"rule":"java:S106","severity":"MINOR","component":"demo:src/App.java","line":1,
                "message":"logger","status":"OPEN"}]}
                """;
        String withNew = """
                {"issues":[
                  {"rule":"java:S106","severity":"MINOR","component":"demo:src/App.java","line":1,
                   "message":"logger","status":"OPEN"},
                  {"rule":"java:S1192","severity":"CRITICAL","component":"demo:src/App.java","line":2,
                   "message":"dup","status":"OPEN"}
                ]}
                """;
        Files.writeString(baseline.resolve("target/sonar-issues.json"), shared, StandardCharsets.UTF_8);
        Files.writeString(patched.resolve("target/sonar-issues.json"), withNew, StandardCharsets.UTF_8);

        SonarProperties props = new SonarProperties();
        props.setEnabled(true);
        props.setProjectKey("demo");
        QualityAnalysisService withSonar = new QualityAnalysisService(
                objectMapper,
                new SonarQualityAnalyzer(props, new SonarQualityClient(objectMapper)));

        QualityAnalysisService.AggregatedQuality result = withSonar.analyze(baseline, patched);
        assertTrue(result.realResults());
        assertTrue(result.toolLabel().contains("sonar"));
        JsonNode metrics = objectMapper.readTree(result.metricsJson());
        assertTrue(metrics.path("sonarEnabled").asBoolean());
        assertTrue(metrics.path("sonarContributed").asBoolean());
        assertFalse(metrics.path("sonar").path("stub").asBoolean());
        assertEquals(1, metrics.path("sonar").path("newFindings").asInt());
        assertEquals(1, metrics.path("sonar").path("preExistingFindings").asInt());
        // Primary tools array still present (checkstyle/pmd/spotbugs or builtin).
        assertTrue(metrics.path("tools").isArray());
        assertTrue(metrics.path("tools").size() >= 1);
    }
}
