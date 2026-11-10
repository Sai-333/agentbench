package com.agentbench.evaluation;

import com.agentbench.evaluation.domain.CaseCategory;
import com.agentbench.evaluation.domain.Evaluation;
import com.agentbench.evaluation.domain.EvaluationScore;
import com.agentbench.evaluation.domain.EvaluationState;
import com.agentbench.evaluation.domain.ScoreStatus;
import com.agentbench.evaluation.domain.ScoringProfile;
import com.agentbench.evaluation.domain.TestCaseResult;
import com.agentbench.evaluation.domain.TestCaseStatus;
import com.agentbench.evaluation.domain.TestPhase;
import com.agentbench.evaluation.domain.TestRun;
import com.agentbench.evaluation.infra.EvaluationRepository;
import com.agentbench.evaluation.service.ScoringService;
import com.agentbench.evaluation.service.TransitionClassificationEngine;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ScoringServiceTest {

    @Mock
    EvaluationRepository evaluationRepository;

    ScoringService scoringService;
    ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        scoringService = new ScoringService(
                evaluationRepository, new TransitionClassificationEngine(), objectMapper);
    }

    @Test
    void computesWeightedScoreWithHiddenPassRateNeverF2p() {
        UUID evalId = UUID.randomUUID();
        UUID orgId = UUID.randomUUID();
        Evaluation evaluation = evaluation(evalId, orgId);

        TestRun baseline = run(evalId, orgId, TestPhase.BASELINE);
        TestRun visible = run(evalId, orgId, TestPhase.VISIBLE_POST);
        TestRun hidden = run(evalId, orgId, TestPhase.HIDDEN_POST);

        when(evaluationRepository.findScore(evalId)).thenReturn(Optional.empty());
        when(evaluationRepository.findScoringProfile(ScoringProfile.DEFAULT_KEY))
                .thenReturn(Optional.of(defaultProfile()));
        when(evaluationRepository.findTestRun(evalId, TestPhase.BASELINE)).thenReturn(Optional.of(baseline));
        when(evaluationRepository.findTestRun(evalId, TestPhase.VISIBLE_POST)).thenReturn(Optional.of(visible));
        when(evaluationRepository.findTestRun(evalId, TestPhase.HIDDEN_POST)).thenReturn(Optional.of(hidden));
        when(evaluationRepository.listTestCaseResults(baseline.id())).thenReturn(List.of(
                caseResult(baseline.id(), orgId, "target.a", TestCaseStatus.FAIL, null, CaseCategory.TARGET),
                caseResult(baseline.id(), orgId, "control.b", TestCaseStatus.PASS, null, CaseCategory.CONTROL)
        ));
        when(evaluationRepository.listTestCaseResults(visible.id())).thenReturn(List.of(
                caseResult(visible.id(), orgId, "target.a", TestCaseStatus.PASS, "F2P", CaseCategory.TARGET),
                caseResult(visible.id(), orgId, "control.b", TestCaseStatus.PASS, "P2P", CaseCategory.CONTROL)
        ));
        when(evaluationRepository.listTestCaseResults(hidden.id())).thenReturn(List.of(
                caseResult(hidden.id(), orgId, "hidden.x", TestCaseStatus.PASS, null, CaseCategory.HIDDEN),
                caseResult(hidden.id(), orgId, "hidden.y", TestCaseStatus.FAIL, null, CaseCategory.HIDDEN)
        ));
        when(evaluationRepository.findQualityReports(evalId)).thenReturn(List.of());

        ObjectNode task = objectMapper.createObjectNode();
        task.putObject("evalConfig").put("requireTargetFailAtBaseline", true);

        scoringService.score(evaluation, task);

        ArgumentCaptor<EvaluationScore> scoreCaptor = ArgumentCaptor.forClass(EvaluationScore.class);
        verify(evaluationRepository).insertScore(scoreCaptor.capture(), anyList());
        EvaluationScore score = scoreCaptor.getValue();
        assertEquals(ScoreStatus.VALID, score.scoreStatus());
        assertTrue(score.weightedTotal().compareTo(BigDecimal.ZERO) > 0);
        // target 1.0*30 + regression 1.0*25 + hidden 0.5*20 + quality 0 + edge 0 + efficiency 0 = 65
        assertEquals(0, score.weightedTotal().compareTo(new BigDecimal("65.0000")));
        assertTrue(score.rawSummaryJson().contains("\"hidden\":0.5") || score.rawSummaryJson().contains("0.5"));
        assertTrue(score.transitionSummaryJson().contains("F2P"));
    }

    @Test
    void invalidBaselineWithholdsWeightedTotal() {
        UUID evalId = UUID.randomUUID();
        UUID orgId = UUID.randomUUID();
        Evaluation evaluation = evaluation(evalId, orgId);
        TestRun baseline = run(evalId, orgId, TestPhase.BASELINE);
        TestRun visible = run(evalId, orgId, TestPhase.VISIBLE_POST);

        when(evaluationRepository.findScore(evalId)).thenReturn(Optional.empty());
        when(evaluationRepository.findScoringProfile(ScoringProfile.DEFAULT_KEY))
                .thenReturn(Optional.of(defaultProfile()));
        when(evaluationRepository.findTestRun(evalId, TestPhase.BASELINE)).thenReturn(Optional.of(baseline));
        when(evaluationRepository.findTestRun(evalId, TestPhase.VISIBLE_POST)).thenReturn(Optional.of(visible));
        when(evaluationRepository.findTestRun(evalId, TestPhase.HIDDEN_POST)).thenReturn(Optional.empty());
        when(evaluationRepository.listTestCaseResults(baseline.id())).thenReturn(List.of(
                caseResult(baseline.id(), orgId, "target.a", TestCaseStatus.PASS, null, CaseCategory.TARGET)
        ));
        when(evaluationRepository.listTestCaseResults(visible.id())).thenReturn(List.of(
                caseResult(visible.id(), orgId, "target.a", TestCaseStatus.PASS, "P2P", CaseCategory.TARGET)
        ));

        ObjectNode task = objectMapper.createObjectNode();
        task.putObject("evalConfig").put("requireTargetFailAtBaseline", true);

        scoringService.score(evaluation, task);

        ArgumentCaptor<EvaluationScore> scoreCaptor = ArgumentCaptor.forClass(EvaluationScore.class);
        verify(evaluationRepository).insertScore(scoreCaptor.capture(), anyList());
        assertEquals(ScoreStatus.INVALID_BASELINE, scoreCaptor.getValue().scoreStatus());
        assertNull(scoreCaptor.getValue().weightedTotal());
    }

    @Test
    void quarantinedCasesExcludedFromScoringDenominators() {
        UUID evalId = UUID.randomUUID();
        UUID orgId = UUID.randomUUID();
        Evaluation evaluation = evaluation(evalId, orgId);
        TestRun baseline = run(evalId, orgId, TestPhase.BASELINE);
        TestRun visible = run(evalId, orgId, TestPhase.VISIBLE_POST);

        when(evaluationRepository.findScore(evalId)).thenReturn(Optional.empty());
        when(evaluationRepository.findScoringProfile(ScoringProfile.DEFAULT_KEY))
                .thenReturn(Optional.of(defaultProfile()));
        when(evaluationRepository.findTestRun(evalId, TestPhase.BASELINE)).thenReturn(Optional.of(baseline));
        when(evaluationRepository.findTestRun(evalId, TestPhase.VISIBLE_POST)).thenReturn(Optional.of(visible));
        when(evaluationRepository.findTestRun(evalId, TestPhase.HIDDEN_POST)).thenReturn(Optional.empty());
        when(evaluationRepository.listTestCaseResults(baseline.id())).thenReturn(List.of(
                caseResult(baseline.id(), orgId, "target.a", TestCaseStatus.FAIL, null, CaseCategory.TARGET),
                quarantinedResult(baseline.id(), orgId, "target.flake", TestCaseStatus.PASS, null, CaseCategory.TARGET)
        ));
        when(evaluationRepository.listTestCaseResults(visible.id())).thenReturn(List.of(
                caseResult(visible.id(), orgId, "target.a", TestCaseStatus.PASS, "F2P", CaseCategory.TARGET),
                quarantinedResult(visible.id(), orgId, "target.flake", TestCaseStatus.FAIL, null, CaseCategory.TARGET),
                caseResult(visible.id(), orgId, "control.b", TestCaseStatus.PASS, "P2P", CaseCategory.CONTROL)
        ));
        when(evaluationRepository.findQualityReports(evalId)).thenReturn(List.of());

        ObjectNode task = objectMapper.createObjectNode();
        task.putObject("evalConfig").put("requireTargetFailAtBaseline", true);

        scoringService.score(evaluation, task);

        ArgumentCaptor<EvaluationScore> scoreCaptor = ArgumentCaptor.forClass(EvaluationScore.class);
        verify(evaluationRepository).insertScore(scoreCaptor.capture(), anyList());
        EvaluationScore score = scoreCaptor.getValue();
        assertEquals(ScoreStatus.VALID, score.scoreStatus());
        // Quarantined FAIL target must not dilute target resolution (still 1.0).
        assertTrue(score.rawSummaryJson().contains("\"target_failure_resolution\":1")
                || score.rawSummaryJson().contains("1.0"));
        assertTrue(score.warningsJson().contains("quarantined"));
    }

    @Test
    void incompleteWhenMissingRuns() {
        UUID evalId = UUID.randomUUID();
        UUID orgId = UUID.randomUUID();
        when(evaluationRepository.findScore(evalId)).thenReturn(Optional.empty());
        when(evaluationRepository.findScoringProfile(ScoringProfile.DEFAULT_KEY))
                .thenReturn(Optional.of(defaultProfile()));
        when(evaluationRepository.findTestRun(evalId, TestPhase.BASELINE)).thenReturn(Optional.empty());
        when(evaluationRepository.findTestRun(evalId, TestPhase.VISIBLE_POST)).thenReturn(Optional.empty());
        when(evaluationRepository.findTestRun(evalId, TestPhase.HIDDEN_POST)).thenReturn(Optional.empty());

        scoringService.score(evaluation(evalId, orgId), objectMapper.createObjectNode());

        ArgumentCaptor<EvaluationScore> scoreCaptor = ArgumentCaptor.forClass(EvaluationScore.class);
        verify(evaluationRepository).insertScore(scoreCaptor.capture(), anyList());
        assertEquals(ScoreStatus.INCOMPLETE, scoreCaptor.getValue().scoreStatus());
        assertNull(scoreCaptor.getValue().weightedTotal());
    }

    private static ScoringProfile defaultProfile() {
        return new ScoringProfile(
                UUID.fromString("a0000000-0000-4000-8000-000000000001"),
                ScoringProfile.DEFAULT_KEY,
                "Default v1",
                "",
                ScoringProfile.DEFAULT_WEIGHTS,
                1,
                true,
                Instant.now()
        );
    }

    private static Evaluation evaluation(UUID id, UUID orgId) {
        Instant now = Instant.now();
        return new Evaluation(
                id, orgId, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                EvaluationState.SCORING, 1, null, null, null, null, "{}", null, false,
                UUID.randomUUID(), now, now, now.minusSeconds(30), null
        );
    }

    private static TestRun run(UUID evalId, UUID orgId, TestPhase phase) {
        return new TestRun(UUID.randomUUID(), evalId, orgId, phase, null, "SUCCEEDED",
                Instant.now(), Instant.now(), "{}");
    }

    private static TestCaseResult caseResult(
            UUID runId, UUID orgId, String key, TestCaseStatus status, String transition, CaseCategory category) {
        return new TestCaseResult(
                UUID.randomUUID(), runId, orgId, key, status, 1, transition, "", null,
                category, false, 1, "{}");
    }

    private static TestCaseResult quarantinedResult(
            UUID runId, UUID orgId, String key, TestCaseStatus status, String transition, CaseCategory category) {
        return new TestCaseResult(
                UUID.randomUUID(), runId, orgId, key, status, 1, transition, "", null,
                category, false, 1, "{\"quarantined\":true}");
    }
}
