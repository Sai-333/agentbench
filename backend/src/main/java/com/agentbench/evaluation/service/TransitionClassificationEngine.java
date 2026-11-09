package com.agentbench.evaluation.service;

import com.agentbench.evaluation.domain.CaseCategory;
import com.agentbench.evaluation.domain.TestCaseResult;
import com.agentbench.evaluation.domain.TestCaseStatus;
import com.agentbench.evaluation.domain.TransitionKind;
import com.agentbench.execution.service.FixtureSuiteRunner;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Classifies stable test identities under comparable baseline vs post-patch conditions.
 * Hidden suites are never labeled F2P/P2P/P2F/F2F — only post-only pass rates.
 */
@Service
public class TransitionClassificationEngine {

    public Optional<TransitionKind> classifyComparable(TestCaseStatus baseline, TestCaseStatus post) {
        if (baseline == null || post == null) {
            return Optional.empty();
        }
        if (!TransitionKind.isComparable(baseline) || !TransitionKind.isComparable(post)) {
            return Optional.empty();
        }
        return Optional.of(TransitionKind.classify(baseline, post));
    }

    /**
     * Visible post results: attach transition labels where baseline+post are PASS/FAIL.
     */
    public List<ClassifiedCase> classifyVisible(
            List<TestCaseResult> baselineResults,
            List<FixtureSuiteRunner.CaseOutcome> postOutcomes,
            Map<String, CaseCategory> categoriesByKey) {
        Map<String, TestCaseStatus> baselineByKey = indexBaseline(baselineResults);
        List<ClassifiedCase> classified = new ArrayList<>();
        for (FixtureSuiteRunner.CaseOutcome outcome : postOutcomes) {
            TestCaseStatus baseline = baselineByKey.get(outcome.caseKey());
            String transition = classifyComparable(baseline, outcome.status())
                    .map(Enum::name)
                    .orElse(null);
            CaseCategory category = categoriesByKey.getOrDefault(outcome.caseKey(), CaseCategory.OTHER);
            classified.add(new ClassifiedCase(
                    outcome.caseKey(),
                    outcome.status(),
                    outcome.durationMs(),
                    outcome.message(),
                    transition,
                    category,
                    false,
                    1
            ));
        }
        return classified;
    }

    /**
     * Hidden post-only: never emit transition labels.
     * Edge-tagged cases keep EDGE category for scoring hooks; others are HIDDEN.
     */
    public List<ClassifiedCase> classifyHiddenPostOnly(
            List<FixtureSuiteRunner.CaseOutcome> postOutcomes,
            Map<String, CaseCategory> categoriesByKey) {
        List<ClassifiedCase> classified = new ArrayList<>();
        for (FixtureSuiteRunner.CaseOutcome outcome : postOutcomes) {
            CaseCategory fromFixture = categoriesByKey.get(outcome.caseKey());
            CaseCategory category = fromFixture == CaseCategory.EDGE ? CaseCategory.EDGE : CaseCategory.HIDDEN;
            classified.add(new ClassifiedCase(
                    outcome.caseKey(),
                    outcome.status(),
                    outcome.durationMs(),
                    outcome.message(),
                    null,
                    category,
                    false,
                    1
            ));
        }
        return classified;
    }

    public List<ClassifiedCase> classifyHiddenPostOnly(List<FixtureSuiteRunner.CaseOutcome> postOutcomes) {
        return classifyHiddenPostOnly(postOutcomes, Map.of());
    }

    public TransitionMatrix summarize(List<ClassifiedCase> visibleClassified) {
        Map<TransitionKind, Integer> counts = new EnumMap<>(TransitionKind.class);
        for (TransitionKind kind : TransitionKind.values()) {
            counts.put(kind, 0);
        }
        int unclassified = 0;
        for (ClassifiedCase c : visibleClassified) {
            if (c.transition() == null) {
                unclassified++;
                continue;
            }
            TransitionKind kind = TransitionKind.valueOf(c.transition());
            counts.merge(kind, 1, Integer::sum);
        }
        return new TransitionMatrix(
                counts.get(TransitionKind.F2P),
                counts.get(TransitionKind.P2P),
                counts.get(TransitionKind.P2F),
                counts.get(TransitionKind.F2F),
                unclassified
        );
    }

    public Map<String, CaseCategory> categoriesFromFixtureCases(
            List<com.fasterxml.jackson.databind.JsonNode> caseNodes,
            boolean hiddenSuite) {
        Map<String, CaseCategory> map = new LinkedHashMap<>();
        for (com.fasterxml.jackson.databind.JsonNode node : caseNodes) {
            String key = node.path("key").asText(null);
            if (key == null || key.isBlank()) {
                continue;
            }
            String role = node.path("role").asText(null);
            if ((role == null || role.isBlank()) && key.toLowerCase().contains("target")) {
                role = "target";
            }
            if ((role == null || role.isBlank()) && key.toLowerCase().contains("edge")) {
                role = "edge";
            }
            if ((role == null || role.isBlank()) && (
                    key.toLowerCase().contains("control") || "control".equalsIgnoreCase(node.path("role").asText()))) {
                role = "control";
            }
            map.put(key, CaseCategory.fromRole(role, hiddenSuite));
        }
        return map;
    }

    private static Map<String, TestCaseStatus> indexBaseline(List<TestCaseResult> baselineResults) {
        Map<String, TestCaseStatus> map = new LinkedHashMap<>();
        if (baselineResults == null) {
            return map;
        }
        for (TestCaseResult result : baselineResults) {
            map.put(result.caseKey(), result.status());
        }
        return map;
    }

    public record ClassifiedCase(
            String caseKey,
            TestCaseStatus status,
            int durationMs,
            String message,
            String transition,
            CaseCategory category,
            boolean flaky,
            int attemptCount
    ) {
        public ClassifiedCase {
            Objects.requireNonNull(caseKey);
            Objects.requireNonNull(status);
            Objects.requireNonNull(category);
        }

        public ClassifiedCase withFlaky(boolean flakyFlag, int attempts) {
            return new ClassifiedCase(caseKey, status, durationMs, message, transition, category, flakyFlag, attempts);
        }
    }

    public record TransitionMatrix(int f2p, int p2p, int p2f, int f2f, int unclassified) {
        public Map<String, Integer> toMap() {
            Map<String, Integer> map = new LinkedHashMap<>();
            map.put("F2P", f2p);
            map.put("P2P", p2p);
            map.put("P2F", p2f);
            map.put("F2F", f2f);
            map.put("unclassified", unclassified);
            return map;
        }
    }
}
