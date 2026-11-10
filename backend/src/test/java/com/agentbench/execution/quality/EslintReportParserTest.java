package com.agentbench.execution.quality;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EslintReportParserTest {

    @Test
    void parsesEslintJsonArray() {
        String json = """
                [
                  {
                    "filePath": "src/index.js",
                    "messages": [
                      {"ruleId": "no-console", "severity": 1, "line": 2, "message": "Unexpected console"},
                      {"ruleId": "eqeqeq", "severity": 2, "line": 5, "message": "Expected ==="}
                    ]
                  }
                ]
                """;
        QualityToolReport report = EslintReportParser.parse(json);
        assertFalse(report.stub());
        assertEquals("eslint", report.tool());
        assertEquals(2, report.findings().size());
        assertEquals("WARNING", report.findings().get(0).severity());
        assertEquals("ERROR", report.findings().get(1).severity());
    }

    @Test
    void emptyAndInvalidAreStubs() {
        assertTrue(EslintReportParser.parse("").stub());
        assertTrue(EslintReportParser.parse("{}").stub());
        assertTrue(EslintReportParser.parse("not-json").stub());
    }
}
