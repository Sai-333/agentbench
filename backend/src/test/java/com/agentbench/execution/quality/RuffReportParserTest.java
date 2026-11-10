package com.agentbench.execution.quality;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuffReportParserTest {

    @Test
    void parsesRuffJsonArray() {
        String json = """
                [
                  {
                    "code": "T201",
                    "message": "print found",
                    "filename": "app.py",
                    "location": {"row": 3, "column": 1}
                  }
                ]
                """;
        QualityToolReport report = RuffReportParser.parse(json);
        assertFalse(report.stub());
        assertEquals("ruff", report.tool());
        assertEquals(1, report.findings().size());
        assertEquals("T201", report.findings().get(0).ruleId());
        assertEquals(3, report.findings().get(0).line());
        assertEquals("app.py", report.findings().get(0).filePath());
    }

    @Test
    void emptyAndInvalidAreStubs() {
        assertTrue(RuffReportParser.parse("").stub());
        assertTrue(RuffReportParser.parse("{}").stub());
        assertTrue(RuffReportParser.parse("not-json").stub());
    }
}
