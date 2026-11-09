package com.agentbench.evaluation;

import com.agentbench.evaluation.domain.TestCaseStatus;
import com.agentbench.evaluation.service.FlakyDetectionService;
import com.agentbench.execution.service.FixtureSuiteRunner;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlakyDetectionServiceTest {

    @Test
    void detectsInconsistentStatusesAcrossAttempts() {
        List<List<FixtureSuiteRunner.CaseOutcome>> attempts = List.of(
                List.of(
                        new FixtureSuiteRunner.CaseOutcome("a", TestCaseStatus.PASS, 1, ""),
                        new FixtureSuiteRunner.CaseOutcome("b", TestCaseStatus.FAIL, 1, "")
                ),
                List.of(
                        new FixtureSuiteRunner.CaseOutcome("a", TestCaseStatus.FAIL, 1, ""),
                        new FixtureSuiteRunner.CaseOutcome("b", TestCaseStatus.FAIL, 1, "")
                )
        );
        Set<String> flaky = FlakyDetectionService.detectFlakyKeys(attempts);
        assertEquals(Set.of("a"), flaky);
    }

    @Test
    void stableResultsAreNotFlaky() {
        List<List<FixtureSuiteRunner.CaseOutcome>> attempts = List.of(
                List.of(new FixtureSuiteRunner.CaseOutcome("a", TestCaseStatus.PASS, 1, "")),
                List.of(new FixtureSuiteRunner.CaseOutcome("a", TestCaseStatus.PASS, 1, ""))
        );
        assertTrue(FlakyDetectionService.detectFlakyKeys(attempts).isEmpty());
    }
}
