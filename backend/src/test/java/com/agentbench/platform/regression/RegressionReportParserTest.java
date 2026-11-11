package com.agentbench.platform.regression;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RegressionReportParserTest {

    @Test
    void junitXmlMapsErrorToFailedAndDerivesCountsFromCases() {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <testsuites>
                  <testsuite name="pytest" tests="4" failures="1" errors="1" skipped="1" time="2.0"
                             timestamp="2026-11-11T04:00:00">
                    <testcase classname="tests.test_regression" name="test_ok" time="1.25"/>
                    <testcase classname="tests.test_regression" name="test_bad" time="0.5">
                      <failure message="AssertionError: boom">trace</failure>
                    </testcase>
                    <testcase classname="tests.test_regression" name="test_err" time="0">
                      <error message="exploded">trace</error>
                    </testcase>
                    <testcase classname="tests.test_regression" name="test_skip" time="0.0">
                      <skipped message="later"/>
                    </testcase>
                  </testsuite>
                </testsuites>
                """;

        RegressionReportParser.ParsedReport report = RegressionReportParser.parse("application/xml", xml);

        assertEquals(4, report.cases().size());
        assertEquals("tests.test_regression::test_ok", report.cases().get(0).name());
        assertEquals(RegressionReportParser.CaseStatus.PASSED, report.cases().get(0).status());
        assertEquals(1250, report.cases().get(0).durationMs());
        assertNull(report.cases().get(0).message());
        assertEquals(RegressionReportParser.CaseStatus.FAILED, report.cases().get(1).status());
        assertEquals("AssertionError: boom", report.cases().get(1).message());
        assertEquals(500, report.cases().get(1).durationMs());
        assertEquals(RegressionReportParser.CaseStatus.FAILED, report.cases().get(2).status());
        assertEquals("exploded", report.cases().get(2).message());
        assertEquals(0, report.cases().get(2).durationMs());
        assertEquals(RegressionReportParser.CaseStatus.SKIPPED, report.cases().get(3).status());
        assertEquals("later", report.cases().get(3).message());
        assertEquals(1, report.summary().passed());
        assertEquals(2, report.summary().failed());
        assertEquals(1, report.summary().skipped());
        assertEquals(report.cases().size(), report.summary().caseCount());
        assertEquals(Instant.parse("2026-11-11T04:00:00Z"), report.hints().startedAt());
        assertEquals(Instant.parse("2026-11-11T04:00:02Z"), report.hints().finishedAt());
    }

    @Test
    void duplicateJunitNamesAreBothKept() {
        String xml = """
                <testsuite name="pytest">
                  <testcase classname="tests.mod" name="test_same" time="0.1"/>
                  <testcase classname="tests.mod" name="test_same" time="0.2"/>
                </testsuite>
                """;
        RegressionReportParser.ParsedReport report = RegressionReportParser.parse("text/xml", xml);
        assertEquals(2, report.cases().size());
        assertEquals(2, report.summary().passed());
        assertEquals(100, report.cases().get(0).durationMs());
        assertEquals(200, report.cases().get(1).durationMs());
    }

    @Test
    void pytestJsonIgnoresLyingSummaryAndMapsOutcomes() {
        String json = """
                {
                  "summary": {"passed": 99, "failed": 0, "skipped": 0, "total": 99},
                  "tests": [
                    {"nodeid": "tests/test_a.py::test_ok", "outcome": "passed", "duration": 0.2},
                    {"nodeid": "tests/test_a.py::test_bad", "outcome": "failed",
                     "call": {"longrepr": "AssertionError: no", "duration": 0.1}},
                    {"nodeid": "tests/test_a.py::test_err", "outcome": "error",
                     "call": {"crash": {"message": "Error: exploded"}}},
                    {"nodeid": "tests/test_a.py::test_skip", "outcome": "skipped",
                     "setup": {"longrepr": "Skipped: blocked"}},
                    {"nodeid": "tests/test_a.py::test_xpass", "outcome": "xpassed", "duration": 0.01},
                    {"nodeid": "tests/test_a.py::test_xfail", "outcome": "xfailed",
                     "call": {"longrepr": "expected"}}
                  ]
                }
                """;

        RegressionReportParser.ParsedReport report = RegressionReportParser.parse("application/json", json);

        assertEquals(6, report.cases().size());
        assertEquals(2, report.summary().passed());
        assertEquals(2, report.summary().failed());
        assertEquals(2, report.summary().skipped());
        assertEquals(200, report.cases().get(0).durationMs());
        assertEquals("AssertionError: no", report.cases().get(1).message());
        assertEquals("Error: exploded", report.cases().get(2).message());
        assertEquals(RegressionReportParser.CaseStatus.FAILED, report.cases().get(2).status());
        assertEquals("Skipped: blocked", report.cases().get(3).message());
        assertEquals(RegressionReportParser.CaseStatus.PASSED, report.cases().get(4).status());
        assertEquals(RegressionReportParser.CaseStatus.SKIPPED, report.cases().get(5).status());
        assertEquals(RegressionReportParser.summarize(report.cases()), report.summary());
    }

    @Test
    void canonicalJsonReadsDurationMsAndGitHints() {
        String json = """
                {
                  "gitRepo": "https://github.com/Sai-333/agentbench",
                  "gitBranch": "selenium-mock",
                  "gitSha": "abc1234",
                  "suiteName": "selenium-mock",
                  "startedAt": "2026-11-11T01:00:00Z",
                  "finishedAt": "2026-11-11T01:02:00Z",
                  "cases": [
                    {"name": "mock-seed::test_passes", "status": "passed", "durationMs": 120},
                    {"name": "mock-seed::test_passes", "status": "passed", "durationMs": 10},
                    {"name": "mock-seed::test_fails", "status": "failed", "durationMs": 80, "message": "mock failure"}
                  ]
                }
                """;

        RegressionReportParser.ParsedReport report = RegressionReportParser.parse("application/json", json);

        assertEquals(3, report.summary().caseCount());
        assertEquals(2, report.summary().passed());
        assertEquals(1, report.summary().failed());
        assertEquals(0, report.summary().skipped());
        assertEquals("https://github.com/Sai-333/agentbench", report.hints().gitRepo());
        assertEquals("selenium-mock", report.hints().suiteName());
        assertEquals(Instant.parse("2026-11-11T01:00:00Z"), report.hints().startedAt());
        assertEquals("mock failure", report.cases().get(2).message());
    }

    @Test
    void junitEnvelopeUsesOuterGitHints() {
        String json = """
                {
                  "reportFormat": "junit-xml",
                  "gitRepo": "https://github.com/Sai-333/agentbench",
                  "gitBranch": "main",
                  "gitSha": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                  "suiteName": "selenium",
                  "report": "<testsuite name=\\"pytest\\"><testcase classname=\\"t\\" name=\\"ok\\" time=\\"0.01\\"/></testsuite>"
                }
                """;
        RegressionReportParser.ParsedReport report = RegressionReportParser.parse("application/json", json);
        assertEquals(1, report.summary().passed());
        assertEquals("selenium", report.hints().suiteName());
        assertEquals("t::ok", report.cases().get(0).name());
        assertEquals(10, report.cases().get(0).durationMs());
    }

    @Test
    void summarizeCountsOnlyTheGivenRows() {
        RegressionReportParser.Summary summary = RegressionReportParser.summarize(List.of(
                new RegressionReportParser.ParsedCase("a", RegressionReportParser.CaseStatus.PASSED, 1, null),
                new RegressionReportParser.ParsedCase("b", RegressionReportParser.CaseStatus.FAILED, 1, "x"),
                new RegressionReportParser.ParsedCase("c", RegressionReportParser.CaseStatus.SKIPPED, 0, null)));
        assertEquals(1, summary.passed());
        assertEquals(1, summary.failed());
        assertEquals(1, summary.skipped());
        assertEquals(3, summary.caseCount());
    }

    @Test
    void rejectsDoctypeUnknownStatusAndNonReports() {
        assertThrows(IllegalArgumentException.class, () -> RegressionReportParser.parse("application/xml", """
                <?xml version="1.0"?>
                <!DOCTYPE foo [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
                <testsuites><testsuite><testcase name="a">&xxe;</testcase></testsuite></testsuites>
                """));
        assertThrows(IllegalArgumentException.class, () -> RegressionReportParser.parse("application/xml", "<html></html>"));
        IllegalArgumentException status = assertThrows(IllegalArgumentException.class,
                () -> RegressionReportParser.parse("application/json", """
                        {"tests":[{"nodeid":"t","outcome":"weird"}]}
                        """));
        assertTrue(status.getMessage().contains("Unrecognized case status"));
        assertThrows(IllegalArgumentException.class, () -> RegressionReportParser.parse("text/plain", "hello"));
    }

    @Test
    void emptyJunitSuiteIsZeroCases() {
        RegressionReportParser.ParsedReport report = RegressionReportParser.parse(
                "application/xml", "<testsuites></testsuites>");
        assertEquals(0, report.summary().caseCount());
        assertEquals(0, report.summary().passed());
        assertEquals(0, report.summary().failed());
        assertEquals(0, report.summary().skipped());
    }
}
