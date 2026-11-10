package com.agentbench.execution.quality;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SonarIssuesParserTest {

    @Test
    void parsesApiShapedFixtureAndStripsProjectPrefix() throws Exception {
        Path fixture = Path.of("src/test/resources/fixtures/sonar-issues-sample.json");
        String json = Files.readString(fixture, StandardCharsets.UTF_8);
        QualityToolReport report = SonarIssuesParser.parse(json, "agentbench-demo", "fixture");
        assertFalse(report.stub());
        assertEquals(2, report.findings().size());
        QualityFinding first = report.findings().getFirst();
        assertEquals("sonar", first.tool());
        assertEquals("java:S106", first.ruleId());
        assertEquals("src/main/java/Demo.java", first.filePath());
        assertEquals(12, first.line());
        assertTrue(first.fingerprint().startsWith("sonar|java:S106|src/main/java/Demo.java|12|"));
    }

    @Test
    void skipsResolvedIssues() {
        String json = """
                {"issues":[
                  {"rule":"java:S1","severity":"MAJOR","component":"p:A.java","line":1,"message":"x","status":"RESOLVED"},
                  {"rule":"java:S2","severity":"MINOR","component":"p:B.java","line":2,"message":"y","status":"OPEN"}
                ]}
                """;
        QualityToolReport report = SonarIssuesParser.parse(json, "p", "t");
        assertEquals(1, report.findings().size());
        assertEquals("java:S2", report.findings().getFirst().ruleId());
    }
}
