package com.agentbench.evaluation.service;

import com.agentbench.evaluation.domain.CaseCategory;
import com.agentbench.evaluation.domain.Evaluation;
import com.agentbench.evaluation.domain.EvaluationScore;
import com.agentbench.evaluation.domain.QualityReport;
import com.agentbench.evaluation.domain.ScoreMetric;
import com.agentbench.evaluation.domain.ScoreStatus;
import com.agentbench.evaluation.domain.ScoringProfile;
import com.agentbench.evaluation.domain.TestCaseResult;
import com.agentbench.evaluation.domain.TestCaseStatus;
import com.agentbench.evaluation.domain.TestPhase;
import com.agentbench.evaluation.domain.TestRun;
import com.agentbench.evaluation.domain.TransitionKind;
import com.agentbench.evaluation.infra.EvaluationRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Computes weighted scores from raw metrics. Raw metrics are always persisted separately.
 * {@code code_quality} uses Checkstyle/SpotBugs/PMD results when present; otherwise stub raw=0
 * with {@code stub=true}. Never invents fake quality scores.
 */
@Service
public class ScoringService {

    private final EvaluationRepository evaluationRepository;
    private final TransitionClassificationEngine classificationEngine;
    private final ObjectMapper objectMapper;

    public ScoringService(
            EvaluationRepository evaluationRepository,
            TransitionClassificationEngine classificationEngine,
            ObjectMapper objectMapper) {
        this.evaluationRepository = evaluationRepository;
        this.classificationEngine = classificationEngine;
        this.objectMapper = objectMapper;
    }

    public EvaluationScore score(Evaluation evaluation, JsonNode taskSnapshot) {
        if (evaluationRepository.findScore(evaluation.id()).isPresent()) {
            return evaluationRepository.findScore(evaluation.id()).orElseThrow();
        }

        ScoringProfile profile = resolveProfile(taskSnapshot);
        List<String> warnings = new ArrayList<>();

        Optional<TestRun> baseline = evaluationRepository.findTestRun(evaluation.id(), TestPhase.BASELINE);
        Optional<TestRun> visiblePost = evaluationRepository.findTestRun(evaluation.id(), TestPhase.VISIBLE_POST);
        Optional<TestRun> hiddenPost = evaluationRepository.findTestRun(evaluation.id(), TestPhase.HIDDEN_POST);

        if (baseline.isEmpty() || visiblePost.isEmpty()) {
            return persistInvalid(evaluation, profile, ScoreStatus.INCOMPLETE,
                    List.of("Missing baseline or visible post results; weighted score withheld"),
                    Map.of(), Map.of());
        }
        if (!"SUCCEEDED".equals(baseline.get().status()) || !"SUCCEEDED".equals(visiblePost.get().status())) {
            return persistInvalid(evaluation, profile, ScoreStatus.INFRA_FAILURE,
                    List.of("Baseline or visible post run did not succeed; weighted score withheld"),
                    Map.of(), Map.of());
        }

        List<TestCaseResult> baselineResults = evaluationRepository.listTestCaseResults(baseline.get().id());
        List<TestCaseResult> visibleResultsAll = evaluationRepository.listTestCaseResults(visiblePost.get().id());
        List<TestCaseResult> hiddenResultsAll = hiddenPost
                .map(run -> evaluationRepository.listTestCaseResults(run.id()))
                .orElse(List.of());

        long quarantinedVisible = visibleResultsAll.stream().filter(ScoringService::isQuarantined).count();
        long quarantinedHidden = hiddenResultsAll.stream().filter(ScoringService::isQuarantined).count();
        if (quarantinedVisible + quarantinedHidden > 0) {
            warnings.add("Excluded " + (quarantinedVisible + quarantinedHidden)
                    + " quarantined case(s) from scoring denominators");
        }
        List<TestCaseResult> baselineResultsScored = baselineResults.stream()
                .filter(r -> !isQuarantined(r))
                .toList();
        List<TestCaseResult> visibleResults = visibleResultsAll.stream()
                .filter(r -> !isQuarantined(r))
                .toList();
        List<TestCaseResult> hiddenResults = hiddenResultsAll.stream()
                .filter(r -> !isQuarantined(r))
                .toList();

        // Invalid baseline: require at least one target FAIL when configured.
        boolean requireTargetFail = taskSnapshot.path("evalConfig").path("requireTargetFailAtBaseline").asBoolean(true);
        List<TestCaseResult> targetBaseline = baselineResultsScored.stream()
                .filter(r -> isTarget(r, taskSnapshot))
                .toList();
        if (requireTargetFail && !targetBaseline.isEmpty()
                && targetBaseline.stream().noneMatch(r -> r.status() == TestCaseStatus.FAIL)) {
            TransitionClassificationEngine.TransitionMatrix emptyMatrix =
                    classificationEngine.summarize(List.of());
            return persistInvalid(evaluation, profile, ScoreStatus.INVALID_BASELINE,
                    List.of("Target bug not observed at baseline; weighted score withheld"),
                    emptyMatrix.toMap(), Map.of("targetCount", targetBaseline.size()));
        }

        List<TransitionClassificationEngine.ClassifiedCase> visibleClassified = visibleResults.stream()
                .map(r -> new TransitionClassificationEngine.ClassifiedCase(
                        r.caseKey(),
                        r.status(),
                        r.durationMs(),
                        r.message(),
                        r.transition(),
                        categoryOf(r),
                        false,
                        1
                ))
                .toList();
        TransitionClassificationEngine.TransitionMatrix matrix = classificationEngine.summarize(visibleClassified);

        Map<String, MetricComputation> metrics = new LinkedHashMap<>();

        // Target resolution
        List<TestCaseResult> targets = visibleResults.stream()
                .filter(r -> categoryOf(r) == CaseCategory.TARGET || isTargetKey(r.caseKey(), taskSnapshot))
                .toList();
        if (targets.isEmpty()) {
            // Fall back: any F2P-eligible baseline FAIL cases as targets when role=target missing
            targets = visibleResults.stream()
                    .filter(r -> TransitionKind.F2P.name().equals(r.transition())
                            || TransitionKind.F2F.name().equals(r.transition()))
                    .toList();
            if (!targets.isEmpty()) {
                warnings.add("No explicit target tags; inferred targets from FAIL-at-baseline cases");
            }
        }
        double targetRaw;
        if (targets.isEmpty()) {
            warnings.add("No target cases configured; target_failure_resolution treated as incomplete input");
            return persistInvalid(evaluation, profile, ScoreStatus.INCOMPLETE,
                    warnings,
                    matrix.toMap(),
                    Map.of("reason", "no_targets"));
        } else {
            long f2p = targets.stream().filter(r -> TransitionKind.F2P.name().equals(r.transition())).count();
            // Flaky targets must not inflate F2P.
            long flakyF2p = targets.stream()
                    .filter(r -> TransitionKind.F2P.name().equals(r.transition()) && r.flaky())
                    .count();
            f2p = f2p - flakyF2p;
            if (flakyF2p > 0) {
                warnings.add("Excluded " + flakyF2p + " flaky F2P target(s) from target resolution");
            }
            targetRaw = (double) f2p / (double) targets.size();
        }
        metrics.put(ScoringProfile.MetricKeys.TARGET_FAILURE_RESOLUTION, MetricComputation.of(targetRaw, false));

        // Regression: 1 - P2F/|P|
        List<TestCaseResult> passedBaseline = visibleResults.stream()
                .filter(r -> TransitionKind.P2P.name().equals(r.transition())
                        || TransitionKind.P2F.name().equals(r.transition()))
                .toList();
        double regressionRaw;
        if (passedBaseline.isEmpty()) {
            regressionRaw = 1.0;
            warnings.add("No baseline-passing controls; regression raw=1 with warning");
        } else {
            long p2f = passedBaseline.stream()
                    .filter(r -> TransitionKind.P2F.name().equals(r.transition()))
                    .count();
            regressionRaw = 1.0 - ((double) p2f / (double) passedBaseline.size());
        }
        metrics.put(ScoringProfile.MetricKeys.REGRESSION, MetricComputation.of(regressionRaw, false));

        // Hidden: post-only pass rate — never F2P
        double hiddenRaw;
        ObjectNode hiddenMeta = objectMapper.createObjectNode();
        hiddenMeta.put("label", "hidden_pass_rate");
        hiddenMeta.put("neverF2P", true);
        if (hiddenResults.isEmpty()) {
            hiddenRaw = 0.0;
            hiddenMeta.put("stub", true);
            hiddenMeta.put("reason", quarantinedHidden > 0 ? "all_hidden_quarantined" : "no_hidden_suite");
            warnings.add("No hidden suite results; hidden raw=0 (structure preserved)");
        } else {
            long pass = hiddenResults.stream().filter(r -> r.status() == TestCaseStatus.PASS).count();
            hiddenRaw = (double) pass / (double) hiddenResults.size();
            hiddenMeta.put("passed", pass);
            hiddenMeta.put("total", hiddenResults.size());
        }
        metrics.put(ScoringProfile.MetricKeys.HIDDEN, new MetricComputation(hiddenRaw, false, hiddenMeta));

        // Code quality: real normalized score from Checkstyle/SpotBugs/PMD aggregate when available
        Optional<QualityReport> quality = evaluationRepository.findQualityReports(evaluation.id()).stream()
                .filter(r -> r.normalizedScore() != null)
                .findFirst()
                .or(() -> evaluationRepository.findQualityReports(evaluation.id()).stream().findFirst());
        ObjectNode qualityMeta = objectMapper.createObjectNode();
        double qualityRaw = 0.0;
        boolean qualityStub = true;
        if (quality.isPresent() && quality.get().normalizedScore() != null) {
            qualityRaw = clamp01(quality.get().normalizedScore().doubleValue());
            qualityMeta.put("tool", quality.get().tool());
            qualityMeta.put("stub", false);
            qualityStub = false;
            try {
                JsonNode qMetrics = objectMapper.readTree(
                        quality.get().metricsJson() == null ? "{}" : quality.get().metricsJson());
                qualityMeta.put("newFindingCount", qMetrics.path("newFindingCount").asInt(0));
                qualityMeta.put("preExistingFindingCount", qMetrics.path("preExistingFindingCount").asInt(0));
                qualityMeta.put("postFindingCount", qMetrics.path("postFindingCount").asInt(0));
            } catch (Exception ignored) {
                // meta enrichment optional
            }
        } else {
            qualityMeta.put("stub", true);
            qualityMeta.put("reason", quality.isPresent()
                    ? "quality_report_without_normalized_score"
                    : "quality_tools_unavailable");
            warnings.add("code_quality stubbed at 0 (no normalized tool score)");
        }
        metrics.put(ScoringProfile.MetricKeys.CODE_QUALITY, new MetricComputation(qualityRaw, qualityStub, qualityMeta));

        // Edge-case suite/tags
        List<TestCaseResult> edgeCases = new ArrayList<>();
        edgeCases.addAll(visibleResults.stream().filter(r -> categoryOf(r) == CaseCategory.EDGE).toList());
        edgeCases.addAll(hiddenResults.stream().filter(r -> categoryOf(r) == CaseCategory.EDGE).toList());
        // Also pick up keys tagged edge in fixture even if category column unset
        if (edgeCases.isEmpty()) {
            edgeCases.addAll(visibleResults.stream().filter(r -> r.caseKey().toLowerCase().contains("edge")).toList());
        }
        ObjectNode edgeMeta = objectMapper.createObjectNode();
        double edgeRaw;
        if (edgeCases.isEmpty()) {
            edgeRaw = 0.0;
            edgeMeta.put("stub", true);
            edgeMeta.put("reason", "no_edge_cases");
            warnings.add("No edge-case tags; edge_case raw=0");
        } else {
            long pass = edgeCases.stream().filter(r ->
                    r.status() == TestCaseStatus.PASS || TransitionKind.F2P.name().equals(r.transition())
                            || TransitionKind.P2P.name().equals(r.transition())).count();
            // For hidden edge cases, PASS only (no transition)
            edgeRaw = (double) pass / (double) edgeCases.size();
            edgeMeta.put("stub", false);
            edgeMeta.put("total", edgeCases.size());
            edgeMeta.put("passed", pass);
        }
        metrics.put(ScoringProfile.MetricKeys.EDGE_CASE, new MetricComputation(edgeRaw, edgeCases.isEmpty(), edgeMeta));

        // Efficiency
        ObjectNode efficiencyMeta = objectMapper.createObjectNode();
        double efficiencyRaw = computeEfficiency(evaluation, taskSnapshot, efficiencyMeta, warnings);
        metrics.put(ScoringProfile.MetricKeys.EFFICIENCY,
                new MetricComputation(efficiencyRaw, efficiencyMeta.path("stub").asBoolean(false), efficiencyMeta));

        BigDecimal weightedTotal = BigDecimal.ZERO;
        List<ScoreMetric> scoreMetrics = new ArrayList<>();
        UUID scoreId = UUID.randomUUID();
        Map<String, Double> weights = profile.weights().isEmpty()
                ? ScoringProfile.DEFAULT_WEIGHTS
                : profile.weights();

        ObjectNode rawSummary = objectMapper.createObjectNode();
        for (Map.Entry<String, Double> weightEntry : ScoringProfile.DEFAULT_WEIGHTS.entrySet()) {
            String key = weightEntry.getKey();
            double weight = weights.getOrDefault(key, weightEntry.getValue());
            MetricComputation computation = metrics.getOrDefault(key, MetricComputation.of(0.0, true));
            double raw = clamp01(computation.raw());
            double weighted = 100.0 * raw * weight;
            weightedTotal = weightedTotal.add(BigDecimal.valueOf(weighted));
            ObjectNode meta = computation.meta() == null
                    ? objectMapper.createObjectNode()
                    : computation.meta().deepCopy();
            if (computation.stub()) {
                meta.put("stub", true);
            }
            rawSummary.put(key, raw);
            scoreMetrics.add(new ScoreMetric(
                    UUID.randomUUID(),
                    scoreId,
                    key,
                    BigDecimal.valueOf(raw).setScale(6, RoundingMode.HALF_UP),
                    BigDecimal.valueOf(weight).setScale(6, RoundingMode.HALF_UP),
                    BigDecimal.valueOf(weighted).setScale(6, RoundingMode.HALF_UP),
                    write(meta)
            ));
        }
        weightedTotal = weightedTotal.setScale(4, RoundingMode.HALF_UP);

        ObjectNode transitionSummary = objectMapper.createObjectNode();
        matrix.toMap().forEach(transitionSummary::put);
        ArrayNode warningsNode = objectMapper.createArrayNode();
        warnings.forEach(warningsNode::add);

        EvaluationScore score = new EvaluationScore(
                scoreId,
                evaluation.id(),
                evaluation.orgId(),
                weightedTotal,
                profile.id() == null ? null : profile.id().toString(),
                profile.profileKey(),
                ScoreStatus.VALID,
                write(transitionSummary),
                write(rawSummary),
                write(warningsNode),
                Instant.now()
        );
        evaluationRepository.insertScore(score, scoreMetrics);
        return score;
    }

    private EvaluationScore persistInvalid(
            Evaluation evaluation,
            ScoringProfile profile,
            ScoreStatus status,
            List<String> warnings,
            Map<String, Integer> transitions,
            Map<String, Object> rawExtra) {
        UUID scoreId = UUID.randomUUID();
        ObjectNode transitionSummary = objectMapper.createObjectNode();
        transitions.forEach(transitionSummary::put);
        ObjectNode rawSummary = objectMapper.createObjectNode();
        rawExtra.forEach((k, v) -> {
            if (v instanceof Number n) {
                rawSummary.put(k, n.doubleValue());
            } else {
                rawSummary.put(k, String.valueOf(v));
            }
        });
        ArrayNode warningsNode = objectMapper.createArrayNode();
        warnings.forEach(warningsNode::add);

        // Persist metric structure with zeros so UI can show breakdown without a misleading total.
        List<ScoreMetric> metrics = new ArrayList<>();
        Map<String, Double> weights = profile.weights().isEmpty()
                ? ScoringProfile.DEFAULT_WEIGHTS
                : profile.weights();
        for (Map.Entry<String, Double> entry : ScoringProfile.DEFAULT_WEIGHTS.entrySet()) {
            double weight = weights.getOrDefault(entry.getKey(), entry.getValue());
            ObjectNode meta = objectMapper.createObjectNode();
            meta.put("withheld", true);
            meta.put("scoreStatus", status.name());
            metrics.add(new ScoreMetric(
                    UUID.randomUUID(),
                    scoreId,
                    entry.getKey(),
                    BigDecimal.ZERO.setScale(6, RoundingMode.HALF_UP),
                    BigDecimal.valueOf(weight).setScale(6, RoundingMode.HALF_UP),
                    BigDecimal.ZERO.setScale(6, RoundingMode.HALF_UP),
                    write(meta)
            ));
        }

        EvaluationScore score = new EvaluationScore(
                scoreId,
                evaluation.id(),
                evaluation.orgId(),
                null,
                profile.id() == null ? null : profile.id().toString(),
                profile.profileKey(),
                status,
                write(transitionSummary),
                write(rawSummary),
                write(warningsNode),
                Instant.now()
        );
        evaluationRepository.insertScore(score, metrics);
        return score;
    }

    private ScoringProfile resolveProfile(JsonNode taskSnapshot) {
        String key = taskSnapshot.path("scoringProfileKey").asText(null);
        if (key == null || key.isBlank()) {
            key = taskSnapshot.path("evalConfig").path("scoringProfileKey").asText(ScoringProfile.DEFAULT_KEY);
        }
        return evaluationRepository.findScoringProfile(key)
                .or(() -> evaluationRepository.findScoringProfile(ScoringProfile.DEFAULT_KEY))
                .orElseGet(() -> new ScoringProfile(
                        null,
                        ScoringProfile.DEFAULT_KEY,
                        "Default v1",
                        "In-memory fallback",
                        ScoringProfile.DEFAULT_WEIGHTS,
                        1,
                        true,
                        Instant.now()
                ));
    }

    private double computeEfficiency(
            Evaluation evaluation,
            JsonNode task,
            ObjectNode meta,
            List<String> warnings) {
        JsonNode budgets = task.path("evalConfig").path("efficiency");
        long soft = budgets.path("budgetSoftMs").asLong(-1);
        long hard = budgets.path("budgetHardMs").asLong(-1);
        if (soft < 0 || hard <= soft) {
            meta.put("stub", true);
            meta.put("reason", "no_efficiency_budgets");
            warnings.add("efficiency stubbed at 0 (no soft/hard budgets configured)");
            return 0.0;
        }
        if (evaluation.startedAt() == null || evaluation.completedAt() == null) {
            // completedAt may not be set yet during SCORING — use now
            Instant end = evaluation.completedAt() == null ? Instant.now() : evaluation.completedAt();
            Instant start = evaluation.startedAt() == null ? end : evaluation.startedAt();
            long durationMs = Duration.between(start, end).toMillis();
            meta.put("durationMs", durationMs);
            meta.put("budgetSoftMs", soft);
            meta.put("budgetHardMs", hard);
            meta.put("stub", false);
            if (durationMs <= soft) {
                return 1.0;
            }
            if (durationMs >= hard) {
                return 0.0;
            }
            return clamp01(1.0 - ((double) (durationMs - soft) / (double) (hard - soft)));
        }
        long durationMs = Duration.between(evaluation.startedAt(), evaluation.completedAt()).toMillis();
        meta.put("durationMs", durationMs);
        meta.put("budgetSoftMs", soft);
        meta.put("budgetHardMs", hard);
        meta.put("stub", false);
        if (durationMs <= soft) {
            return 1.0;
        }
        if (durationMs >= hard) {
            return 0.0;
        }
        return clamp01(1.0 - ((double) (durationMs - soft) / (double) (hard - soft)));
    }

    private boolean isTarget(TestCaseResult result, JsonNode task) {
        if (categoryOf(result) == CaseCategory.TARGET) {
            return true;
        }
        return isTargetKey(result.caseKey(), task);
    }

    private boolean isTargetKey(String caseKey, JsonNode task) {
        if (caseKey != null && caseKey.toLowerCase().contains("target")) {
            return true;
        }
        JsonNode suites = task.path("suites");
        if (!suites.isArray()) {
            return false;
        }
        for (JsonNode suite : suites) {
            if (!"VISIBLE".equalsIgnoreCase(suite.path("visibility").asText())) {
                continue;
            }
            String content = suite.path("fixtureContent").asText("");
            try {
                JsonNode fixture = objectMapper.readTree(content.isBlank() ? "{}" : content);
                JsonNode cases = fixture.path("cases");
                if (cases.isArray()) {
                    for (JsonNode c : cases) {
                        if (caseKey.equals(c.path("key").asText())
                                && "target".equalsIgnoreCase(c.path("role").asText())) {
                            return true;
                        }
                    }
                }
            } catch (Exception ignored) {
                // ignore parse errors
            }
        }
        return false;
    }

    private static CaseCategory categoryOf(TestCaseResult result) {
        if (result.category() != null) {
            return result.category();
        }
        if (result.caseKey() != null && result.caseKey().toLowerCase().contains("target")) {
            return CaseCategory.TARGET;
        }
        if (result.caseKey() != null && result.caseKey().toLowerCase().contains("edge")) {
            return CaseCategory.EDGE;
        }
        if (result.caseKey() != null && result.caseKey().toLowerCase().contains("control")) {
            return CaseCategory.CONTROL;
        }
        return CaseCategory.OTHER;
    }

    private static boolean isQuarantined(TestCaseResult result) {
        return FlakeQuarantineService.isQuarantinedMeta(result.metaJson());
    }

    private static double clamp01(double value) {
        if (value < 0) {
            return 0;
        }
        if (value > 1) {
            return 1;
        }
        return value;
    }

    private String write(Object node) {
        try {
            return objectMapper.writeValueAsString(node);
        } catch (Exception ex) {
            return "{}";
        }
    }

    private record MetricComputation(double raw, boolean stub, ObjectNode meta) {
        static MetricComputation of(double raw, boolean stub) {
            return new MetricComputation(raw, stub, null);
        }
    }
}
