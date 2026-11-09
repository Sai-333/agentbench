package com.agentbench.execution;

import com.agentbench.evaluation.domain.TestCaseStatus;
import com.agentbench.execution.service.JUnitXmlParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JUnitXmlParserTest {

    private static final String SAMPLE = """
            <?xml version="1.0" encoding="UTF-8"?>
            <testsuite name="Demo" tests="3" failures="1" errors="1" skipped="0">
              <testcase classname="com.example.BugTest" name="targetNullSafe" time="0.012"/>
              <testcase classname="com.example.BugTest" name="controlStillCompiles" time="0.004">
                <failure message="expected true">boom</failure>
              </testcase>
              <testcase classname="com.example.BugTest" name="edgeEmpty" time="0.001">
                <error message="NPE">stack</error>
              </testcase>
            </testsuite>
            """;

    @Test
    void parsesSurefireStyleXml() {
        var outcomes = JUnitXmlParser.parseXml(SAMPLE);
        assertEquals(3, outcomes.size());
        assertEquals("com.example.BugTest#targetNullSafe", outcomes.get(0).caseKey());
        assertEquals(TestCaseStatus.PASS, outcomes.get(0).status());
        assertEquals(TestCaseStatus.FAIL, outcomes.get(1).status());
        assertEquals(TestCaseStatus.ERROR, outcomes.get(2).status());
        assertTrue(outcomes.get(0).durationMs() >= 1);
    }

    @Test
    void parsesReportsDirectory(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("TEST-BugTest.xml"), SAMPLE);
        var outcomes = JUnitXmlParser.parseDirectory(dir);
        assertEquals(3, outcomes.size());
        assertTrue(JUnitXmlParser.looksLikeJUnitXml(SAMPLE));
    }
}
