package com.agentbench.evaluation.service;

import com.agentbench.evaluation.domain.TestCaseStatus;
import com.agentbench.evaluation.domain.TestPhase;
import com.agentbench.evaluation.infra.EvaluationRepository;
import com.agentbench.execution.service.FixtureSuiteRunner;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Configurable repeated-run flaky detection hooks.
 * When repeatRuns &gt; 1, re-executes the suite and marks cases with inconsistent statuses.
 */
@Service
public class FlakyDetectionService {

    private final EvaluationRepository evaluationRepository;
    private final int defaultRepeatRuns;

    public FlakyDetectionService(
            EvaluationRepository evaluationRepository,
            @Value("${agentbench.evaluation.flaky.repeat-runs:1}") int defaultRepeatRuns) {
        this.evaluationRepository = evaluationRepository;
        this.defaultRepeatRuns = Math.max(1, Math.min(defaultRepeatRuns, 10));
    }

    public int resolveRepeatRuns(Integer profileOrConfigOverride) {
        if (profileOrConfigOverride == null || profileOrConfigOverride < 1) {
            return defaultRepeatRuns;
        }
        return Math.min(profileOrConfigOverride, 10);
    }

    /**
     * Runs the supplier once (canonical) plus optional repeats. Returns the first-run outcomes
     * with flaky flags applied when statuses disagree across attempts.
     */
    public FlakyAwareResult runWithFlakyDetection(
            UUID evaluationId,
            UUID orgId,
            UUID testRunId,
            TestPhase phase,
            int repeatRuns,
            Supplier<List<FixtureSuiteRunner.CaseOutcome>> suiteRunner) {
        int runs = resolveRepeatRuns(repeatRuns);
        List<List<FixtureSuiteRunner.CaseOutcome>> attempts = new ArrayList<>();
        List<FixtureSuiteRunner.CaseOutcome> first = suiteRunner.get();
        attempts.add(first);

        for (int i = 2; i <= runs; i++) {
            attempts.add(suiteRunner.get());
        }

        Map<String, List<TestCaseStatus>> statusesByKey = new LinkedHashMap<>();
        for (int attemptIndex = 0; attemptIndex < attempts.size(); attemptIndex++) {
            for (FixtureSuiteRunner.CaseOutcome outcome : attempts.get(attemptIndex)) {
                statusesByKey.computeIfAbsent(outcome.caseKey(), k -> new ArrayList<>()).add(outcome.status());
                evaluationRepository.insertFlakyAttempt(
                        evaluationId,
                        orgId,
                        testRunId,
                        phase,
                        attemptIndex + 1,
                        outcome.caseKey(),
                        outcome.status()
                );
            }
        }

        Set<String> flakyKeys = new LinkedHashSet<>();
        for (Map.Entry<String, List<TestCaseStatus>> entry : statusesByKey.entrySet()) {
            Set<TestCaseStatus> distinct = new LinkedHashSet<>(entry.getValue());
            // Ignore SKIP-only noise; flaky when PASS/FAIL/ERROR disagree.
            distinct.remove(TestCaseStatus.SKIP);
            if (distinct.size() > 1) {
                flakyKeys.add(entry.getKey());
            }
        }

        List<FixtureSuiteRunner.CaseOutcome> canonical = new ArrayList<>();
        for (FixtureSuiteRunner.CaseOutcome outcome : first) {
            canonical.add(outcome);
        }
        return new FlakyAwareResult(canonical, flakyKeys, runs);
    }

    /** Pure helper for unit tests without persistence. */
    public static Set<String> detectFlakyKeys(List<List<FixtureSuiteRunner.CaseOutcome>> attempts) {
        Map<String, Set<TestCaseStatus>> statuses = new LinkedHashMap<>();
        for (List<FixtureSuiteRunner.CaseOutcome> attempt : attempts) {
            for (FixtureSuiteRunner.CaseOutcome outcome : attempt) {
                statuses.computeIfAbsent(outcome.caseKey(), k -> new LinkedHashSet<>()).add(outcome.status());
            }
        }
        Set<String> flaky = new LinkedHashSet<>();
        for (Map.Entry<String, Set<TestCaseStatus>> entry : statuses.entrySet()) {
            Set<TestCaseStatus> distinct = new LinkedHashSet<>(entry.getValue());
            distinct.remove(TestCaseStatus.SKIP);
            if (distinct.size() > 1) {
                flaky.add(entry.getKey());
            }
        }
        return flaky;
    }

    public record FlakyAwareResult(
            List<FixtureSuiteRunner.CaseOutcome> canonicalOutcomes,
            Set<String> flakyCaseKeys,
            int attemptCount
    ) {
    }
}
