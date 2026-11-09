package com.agentbench.evaluation;

import com.agentbench.evaluation.domain.CaseCategory;
import com.agentbench.evaluation.domain.TestCaseResult;
import com.agentbench.evaluation.domain.TestCaseStatus;
import com.agentbench.evaluation.domain.TransitionKind;
import com.agentbench.evaluation.service.TransitionClassificationEngine;
import com.agentbench.execution.service.FixtureSuiteRunner;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TransitionClassificationEngineTest {

    private final TransitionClassificationEngine engine = new TransitionClassificationEngine();

    @Test
    void classifiesF2pP2pP2fF2f() {
        assertEquals(TransitionKind.F2P, TransitionKind.classify(TestCaseStatus.FAIL, TestCaseStatus.PASS));
        assertEquals(TransitionKind.P2P, TransitionKind.classify(TestCaseStatus.PASS, TestCaseStatus.PASS));
        assertEquals(TransitionKind.P2F, TransitionKind.classify(TestCaseStatus.PASS, TestCaseStatus.FAIL));
        assertEquals(TransitionKind.F2F, TransitionKind.classify(TestCaseStatus.FAIL, TestCaseStatus.FAIL));
    }

    @Test
    void skipsNonComparableStatuses() {
        assertTrue(engine.classifyComparable(TestCaseStatus.ERROR, TestCaseStatus.PASS).isEmpty());
        assertTrue(engine.classifyComparable(TestCaseStatus.PASS, TestCaseStatus.SKIP).isEmpty());
    }

    @Test
    void visibleMatrixUsesStableIdentities() {
        UUID runId = UUID.randomUUID();
        UUID orgId = UUID.randomUUID();
        List<TestCaseResult> baseline = List.of(
                result(runId, orgId, "target.a", TestCaseStatus.FAIL),
                result(runId, orgId, "control.b", TestCaseStatus.PASS),
                result(runId, orgId, "control.c", TestCaseStatus.PASS),
                result(runId, orgId, "target.d", TestCaseStatus.FAIL)
        );
        List<FixtureSuiteRunner.CaseOutcome> post = List.of(
                new FixtureSuiteRunner.CaseOutcome("target.a", TestCaseStatus.PASS, 1, ""),
                new FixtureSuiteRunner.CaseOutcome("control.b", TestCaseStatus.PASS, 1, ""),
                new FixtureSuiteRunner.CaseOutcome("control.c", TestCaseStatus.FAIL, 1, ""),
                new FixtureSuiteRunner.CaseOutcome("target.d", TestCaseStatus.FAIL, 1, "")
        );
        Map<String, CaseCategory> categories = Map.of(
                "target.a", CaseCategory.TARGET,
                "control.b", CaseCategory.CONTROL,
                "control.c", CaseCategory.CONTROL,
                "target.d", CaseCategory.TARGET
        );
        var classified = engine.classifyVisible(baseline, post, categories);
        var matrix = engine.summarize(classified);
        assertEquals(1, matrix.f2p());
        assertEquals(1, matrix.p2p());
        assertEquals(1, matrix.p2f());
        assertEquals(1, matrix.f2f());
    }

    @Test
    void hiddenNeverGetsTransitionLabels() {
        List<FixtureSuiteRunner.CaseOutcome> post = List.of(
                new FixtureSuiteRunner.CaseOutcome("hidden.x", TestCaseStatus.PASS, 1, ""),
                new FixtureSuiteRunner.CaseOutcome("edge.y", TestCaseStatus.FAIL, 1, "")
        );
        var classified = engine.classifyHiddenPostOnly(post, Map.of(
                "hidden.x", CaseCategory.HIDDEN,
                "edge.y", CaseCategory.EDGE
        ));
        assertEquals(2, classified.size());
        assertTrue(classified.stream().allMatch(c -> c.transition() == null));
        assertEquals(CaseCategory.HIDDEN, classified.get(0).category());
        assertEquals(CaseCategory.EDGE, classified.get(1).category());
    }

    private static TestCaseResult result(UUID runId, UUID orgId, String key, TestCaseStatus status) {
        return new TestCaseResult(
                UUID.randomUUID(), runId, orgId, key, status, 1, null, "", null,
                CaseCategory.OTHER, false, 1, "{}");
    }
}
