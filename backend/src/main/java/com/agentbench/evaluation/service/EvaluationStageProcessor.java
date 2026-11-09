package com.agentbench.evaluation.service;

import com.agentbench.agents.domain.Agent;
import com.agentbench.agents.domain.spi.AgentInvocationResult;
import com.agentbench.agents.infra.AgentRepository;
import com.agentbench.agents.service.AgentInvocationService;
import com.agentbench.evaluation.domain.CaseCategory;
import com.agentbench.evaluation.domain.Evaluation;
import com.agentbench.evaluation.domain.EvaluationState;
import com.agentbench.evaluation.domain.EvaluationStateMachine;
import com.agentbench.evaluation.domain.FailureClass;
import com.agentbench.evaluation.domain.FailureCode;
import com.agentbench.evaluation.domain.OrgEvaluationLimits;
import com.agentbench.evaluation.domain.PatchArtifact;
import com.agentbench.evaluation.domain.QualityReport;
import com.agentbench.evaluation.domain.ScoringProfile;
import com.agentbench.evaluation.domain.TestCaseResult;
import com.agentbench.evaluation.domain.TestCaseStatus;
import com.agentbench.evaluation.domain.TestPhase;
import com.agentbench.evaluation.domain.TestRun;
import com.agentbench.evaluation.infra.EvaluationRepository;
import com.agentbench.execution.domain.ExecutionStage;
import com.agentbench.execution.quality.QualityAnalysisService;
import com.agentbench.execution.service.ExecutionIsolatorService;
import com.agentbench.execution.service.FixtureSuiteRunner;
import com.agentbench.execution.service.PatchValidator;
import com.agentbench.execution.service.SuiteExecutionService;
import com.agentbench.execution.service.WorkspaceProvisioner;
import com.agentbench.platform.error.ApiException;
import com.agentbench.platform.infra.storage.ArtifactRetentionService;
import com.agentbench.platform.infra.storage.ArtifactStorage;
import com.agentbench.agents.infra.SecretRedactor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Idempotent stage executor. Re-entry with the same (evaluation, state, attempt)
 * short-circuits when a stage attempt or processed message already exists.
 */
@Service
public class EvaluationStageProcessor {

    private static final Logger log = LoggerFactory.getLogger(EvaluationStageProcessor.class);

    private final EvaluationRepository evaluationRepository;
    private final EvaluationService evaluationService;
    private final AgentRepository agentRepository;
    private final WorkspaceProvisioner workspaceProvisioner;
    private final SuiteExecutionService suiteExecutionService;
    private final PatchValidator patchValidator;
    private final AgentInvocationService agentInvocationService;
    private final ArtifactStorage artifactStorage;
    private final TransitionClassificationEngine classificationEngine;
    private final FlakyDetectionService flakyDetectionService;
    private final ScoringService scoringService;
    private final QualityAnalysisService qualityAnalysisService;
    private final ExecutionIsolatorService executionIsolatorService;
    private final ArtifactRetentionService artifactRetentionService;
    private final ObjectMapper objectMapper;
    private final int maxAttempts;

    public EvaluationStageProcessor(
            EvaluationRepository evaluationRepository,
            EvaluationService evaluationService,
            AgentRepository agentRepository,
            WorkspaceProvisioner workspaceProvisioner,
            SuiteExecutionService suiteExecutionService,
            PatchValidator patchValidator,
            AgentInvocationService agentInvocationService,
            ArtifactStorage artifactStorage,
            TransitionClassificationEngine classificationEngine,
            FlakyDetectionService flakyDetectionService,
            ScoringService scoringService,
            QualityAnalysisService qualityAnalysisService,
            ExecutionIsolatorService executionIsolatorService,
            ArtifactRetentionService artifactRetentionService,
            ObjectMapper objectMapper,
            @Value("${agentbench.evaluation.max-stage-attempts:5}") int maxAttempts) {
        this.evaluationRepository = evaluationRepository;
        this.evaluationService = evaluationService;
        this.agentRepository = agentRepository;
        this.workspaceProvisioner = workspaceProvisioner;
        this.suiteExecutionService = suiteExecutionService;
        this.patchValidator = patchValidator;
        this.agentInvocationService = agentInvocationService;
        this.artifactStorage = artifactStorage;
        this.classificationEngine = classificationEngine;
        this.flakyDetectionService = flakyDetectionService;
        this.scoringService = scoringService;
        this.qualityAnalysisService = qualityAnalysisService;
        this.executionIsolatorService = executionIsolatorService;
        this.artifactRetentionService = artifactRetentionService;
        this.objectMapper = objectMapper;
        this.maxAttempts = maxAttempts;
    }

    @Transactional
    public void process(EvaluationCommand command) {
        if (evaluationRepository.isMessageProcessed(command.messageId())) {
            log.info("Skipping duplicate message {}", command.messageId());
            return;
        }

        Evaluation evaluation = evaluationRepository.findById(command.evaluationId())
                .orElseThrow(() -> ApiException.notFound("Evaluation not found for command"));

        if (!evaluation.orgId().equals(command.orgId())) {
            throw ApiException.forbidden("Evaluation org mismatch");
        }

        if (evaluation.state().isTerminal()) {
            evaluationRepository.markMessageProcessed(command.messageId(), evaluation.id(), evaluation.orgId());
            return;
        }

        if (evaluation.cancelRequested() && evaluation.state().isCancelable()) {
            evaluationService.transitionTo(
                    evaluation,
                    EvaluationState.CANCELLED,
                    "Cancel requested",
                    "worker",
                    FailureClass.USER,
                    FailureCode.CANCELLED_BY_USER.name(),
                    "Cancelled by user"
            );
            evaluationRepository.markMessageProcessed(command.messageId(), evaluation.id(), evaluation.orgId());
            return;
        }

        if (evaluation.state() == EvaluationState.QUEUED && command.targetState() == EvaluationState.PREPARING_REPO) {
            OrgEvaluationLimits limits = evaluationRepository.getOrDefaultLimits(evaluation.orgId());
            int active = evaluationRepository.countByStates(evaluation.orgId(), activeNonQueued());
            if (active >= limits.maxConcurrentEvaluations()) {
                String retryMessageId = "eval-" + evaluation.id() + "-QUEUED-retry-" + UUID.randomUUID();
                enqueue(evaluation, EvaluationState.PREPARING_REPO, evaluation.attempt(), retryMessageId);
                evaluationRepository.markMessageProcessed(command.messageId(), evaluation.id(), evaluation.orgId());
                return;
            }
        }

        EvaluationState expectedCurrent = predecessor(command.targetState());
        boolean alreadyInTarget = evaluation.state() == command.targetState();
        if (evaluation.state() != expectedCurrent && !alreadyInTarget) {
            evaluationRepository.markMessageProcessed(command.messageId(), evaluation.id(), evaluation.orgId());
            return;
        }

        boolean claimed = evaluationRepository.tryClaimStage(
                evaluation.id(), evaluation.orgId(), command.targetState(), command.attempt(), command.messageId());
        if (!claimed) {
            evaluationRepository.markMessageProcessed(command.messageId(), evaluation.id(), evaluation.orgId());
            return;
        }

        try {
            if (!alreadyInTarget) {
                evaluationService.transitionTo(
                        evaluation,
                        command.targetState(),
                        "Worker claimed stage",
                        "worker",
                        null,
                        null,
                        null
                );
            }
            Evaluation current = evaluationRepository.findById(evaluation.id()).orElseThrow();
            executeStage(current, command);
            evaluationRepository.completeStageAttempt(
                    evaluation.id(), command.targetState(), command.attempt(), "SUCCEEDED", null, null);
            evaluationRepository.markMessageProcessed(command.messageId(), evaluation.id(), evaluation.orgId());

            Evaluation after = evaluationRepository.findById(evaluation.id()).orElseThrow();
            if (!after.state().isTerminal() && after.state() == command.targetState()) {
                EvaluationState next = EvaluationStateMachine.nextHappyPath(after.state());
                String nextMessageId = "eval-" + after.id() + "-" + next.name() + "-" + after.attempt() + "-" + UUID.randomUUID();
                enqueue(after, next, after.attempt(), nextMessageId);
            }
        } catch (ApiException ex) {
            handleStageFailure(evaluationRepository.findById(evaluation.id()).orElse(evaluation),
                    command, ex.getCode(), ex.getMessage());
        } catch (Exception ex) {
            log.error("Stage failed for evaluation {}", evaluation.id(), ex);
            handleStageFailure(evaluationRepository.findById(evaluation.id()).orElse(evaluation),
                    command, FailureCode.INTERNAL.name(), "Internal stage failure");
        }
    }

    private void executeStage(Evaluation evaluation, EvaluationCommand command) throws Exception {
        JsonNode snapshot = objectMapper.readTree(evaluation.snapshotJson());
        JsonNode task = snapshot.path("task");
        OrgEvaluationLimits limits = evaluationRepository.getOrDefaultLimits(evaluation.orgId());

        switch (command.targetState()) {
            case PREPARING_REPO -> {
                Path workspace = workspaceProvisioner.provision(evaluation.orgId(), evaluation.id(), task);
                evaluationRepository.updateLifecycle(
                        evaluation.orgId(),
                        evaluation.id(),
                        evaluation.state(),
                        evaluation.attempt(),
                        null,
                        null,
                        null,
                        workspace.toString(),
                        evaluation.cancelRequested(),
                        evaluation.startedAt() == null ? Instant.now() : evaluation.startedAt(),
                        null
                );
            }
            case RUNNING_BASELINE -> runBaseline(evaluation, task);
            case INVOKING_AGENT, COLLECTING_PATCH -> {
                if (evaluationRepository.findPatch(evaluation.id()).isPresent()) {
                    return;
                }
                Agent agent = agentRepository.find(evaluation.orgId(), evaluation.agentId())
                        .orElseThrow(() -> ApiException.notFound("Agent not found"));
                String visibleContext = buildVisibleAgentContext(task);
                String referenceHint = task.hasNonNull("referencePatch") ? task.get("referencePatch").asText("") : null;
                AgentInvocationResult result = agentInvocationService.invokeForEvaluation(
                        agent,
                        evaluation.id(),
                        evaluation.taskId(),
                        task.path("prompt").asText(""),
                        visibleContext,
                        referenceHint
                );
                storePatch(evaluation, result.patchContent(), limits.maxPatchBytes());
            }
            case VALIDATING_PATCH -> {
                PatchArtifact patch = evaluationRepository.findPatch(evaluation.id())
                        .orElseThrow(() -> new ApiException(
                                org.springframework.http.HttpStatus.BAD_REQUEST,
                                FailureCode.PATCH_INVALID.name(),
                                "Patch missing for validation"));
                String patchText = readPatch(patch);
                List<String> allowed = allowedPaths(task);
                int maxBytes = Math.min(limits.maxPatchBytes(), task.path("maxPatchBytes").asInt(limits.maxPatchBytes()));
                patchValidator.validate(patchText, maxBytes, allowed);
            }
            case APPLYING_PATCH -> {
                PatchArtifact patch = evaluationRepository.findPatch(evaluation.id())
                        .orElseThrow(() -> new ApiException(
                                org.springframework.http.HttpStatus.BAD_REQUEST,
                                FailureCode.PATCH_INVALID.name(),
                                "Patch missing for apply"));
                Path baselineWs = workspaceProvisioner.requireExisting(evaluation.workspaceUri());
                Path clean = workspaceProvisioner.cleanClone(baselineWs, evaluation.orgId(), evaluation.id(), "patched");
                patchValidator.applyUnifiedDiff(clean, readPatch(patch));
                evaluationRepository.updateLifecycle(
                        evaluation.orgId(),
                        evaluation.id(),
                        evaluation.state(),
                        evaluation.attempt(),
                        null, null, null,
                        clean.toString(),
                        evaluation.cancelRequested(),
                        evaluation.startedAt(),
                        null
                );
            }
            case RUNNING_VISIBLE_TESTS -> runPostVisible(evaluation, task);
            case RUNNING_HIDDEN_TESTS -> runHiddenPost(evaluation, task);
            case ANALYZING_QUALITY -> analyzeQuality(evaluation, task);
            case SCORING -> {
                scoringService.score(evaluation, task);
            }
            case COMPLETED -> {
                // Terminal transition target — no side effects.
            }
            default -> throw new IllegalStateException("Unexpected stage " + command.targetState());
        }
    }

    private void runBaseline(Evaluation evaluation, JsonNode task) {
        if (evaluationRepository.findTestRun(evaluation.id(), TestPhase.BASELINE).isPresent()) {
            return;
        }
        Path workspace = workspaceProvisioner.requireExisting(evaluation.workspaceUri());
        JsonNode visible = findSuite(task, "VISIBLE");
        UUID suiteId = visible.hasNonNull("id") ? UUID.fromString(visible.get("id").asText()) : null;
        Instant started = Instant.now();
        TestRun run = new TestRun(
                UUID.randomUUID(), evaluation.id(), evaluation.orgId(), TestPhase.BASELINE, suiteId,
                "RUNNING", started, null, "{\"runner\":\"suite-v1\",\"phase\":\"BASELINE\"}");
        evaluationRepository.insertTestRun(run);

        int repeatRuns = resolveFlakyRepeats(task);
        FlakyDetectionService.FlakyAwareResult flakyAware = flakyDetectionService.runWithFlakyDetection(
                evaluation.id(),
                evaluation.orgId(),
                run.id(),
                TestPhase.BASELINE,
                repeatRuns,
                () -> suiteExecutionService.run(workspace, visible, FixtureSuiteRunner.Phase.BASELINE)
        );

        Map<String, CaseCategory> categories = classificationEngine.categoriesFromFixtureCases(
                suiteExecutionService.listCaseNodes(visible), false);

        boolean targetFailed = false;
        boolean anyFail = false;
        for (FixtureSuiteRunner.CaseOutcome outcome : flakyAware.canonicalOutcomes()) {
            CaseCategory category = categories.getOrDefault(
                    outcome.caseKey(),
                    CaseCategory.fromRole(inferRole(outcome.caseKey()), false));
            boolean flaky = flakyAware.flakyCaseKeys().contains(outcome.caseKey());
            evaluationRepository.insertTestCaseResult(new TestCaseResult(
                    UUID.randomUUID(), run.id(), evaluation.orgId(), outcome.caseKey(),
                    outcome.status(), outcome.durationMs(), null, outcome.message(), null,
                    category, flaky, flakyAware.attemptCount(), "{}"));
            if (outcome.status() == TestCaseStatus.FAIL) {
                anyFail = true;
                if (category == CaseCategory.TARGET || outcome.caseKey().toLowerCase().contains("target")) {
                    targetFailed = true;
                }
            }
        }
        evaluationRepository.finishTestRun(run.id(), "SUCCEEDED", Instant.now());

        JsonNode requireTargetFail = task.path("evalConfig").path("requireTargetFailAtBaseline");
        boolean require = requireTargetFail.isMissingNode() || requireTargetFail.asBoolean(true);
        if (require && !anyFail && !targetFailed && !flakyAware.canonicalOutcomes().isEmpty()) {
            boolean hasTargetRole = categories.containsValue(CaseCategory.TARGET)
                    || flakyAware.canonicalOutcomes().stream()
                    .anyMatch(o -> o.caseKey().toLowerCase().contains("target"));
            if (hasTargetRole) {
                throw new ApiException(
                        org.springframework.http.HttpStatus.CONFLICT,
                        FailureCode.BASELINE_NOT_REPRODUCIBLE.name(),
                        "Target bug not observed at baseline");
            }
        }
    }

    private void runPostVisible(Evaluation evaluation, JsonNode task) {
        if (evaluationRepository.findTestRun(evaluation.id(), TestPhase.VISIBLE_POST).isPresent()) {
            return;
        }
        Path workspace = workspaceProvisioner.requireExisting(evaluation.workspaceUri());
        JsonNode visible = findSuite(task, "VISIBLE");
        UUID suiteId = visible.hasNonNull("id") ? UUID.fromString(visible.get("id").asText()) : null;
        TestRun run = new TestRun(
                UUID.randomUUID(), evaluation.id(), evaluation.orgId(), TestPhase.VISIBLE_POST, suiteId,
                "RUNNING", Instant.now(), null, "{\"runner\":\"suite-v1\",\"phase\":\"VISIBLE_POST\"}");
        evaluationRepository.insertTestRun(run);

        int repeatRuns = resolveFlakyRepeats(task);
        FlakyDetectionService.FlakyAwareResult flakyAware = flakyDetectionService.runWithFlakyDetection(
                evaluation.id(),
                evaluation.orgId(),
                run.id(),
                TestPhase.VISIBLE_POST,
                repeatRuns,
                () -> suiteExecutionService.run(workspace, visible, FixtureSuiteRunner.Phase.POST)
        );

        TestRun baseline = evaluationRepository.findTestRun(evaluation.id(), TestPhase.BASELINE).orElse(null);
        List<TestCaseResult> baselineResults = baseline == null
                ? List.of()
                : evaluationRepository.listTestCaseResults(baseline.id());

        Map<String, CaseCategory> categories = classificationEngine.categoriesFromFixtureCases(
                suiteExecutionService.listCaseNodes(visible), false);

        List<TransitionClassificationEngine.ClassifiedCase> classified =
                classificationEngine.classifyVisible(baselineResults, flakyAware.canonicalOutcomes(), categories);

        for (TransitionClassificationEngine.ClassifiedCase outcome : classified) {
            boolean flaky = flakyAware.flakyCaseKeys().contains(outcome.caseKey());
            // Flaky F2P must not be trusted as a clean fix signal — drop transition label.
            String transition = outcome.transition();
            if (flaky && "F2P".equals(transition)) {
                transition = null;
            }
            evaluationRepository.insertTestCaseResult(new TestCaseResult(
                    UUID.randomUUID(), run.id(), evaluation.orgId(), outcome.caseKey(),
                    outcome.status(), outcome.durationMs(), transition, outcome.message(), null,
                    outcome.category(), flaky, flakyAware.attemptCount(),
                    flaky ? "{\"flaky\":true}" : "{}"));
        }
        evaluationRepository.finishTestRun(run.id(), "SUCCEEDED", Instant.now());
    }

    /**
     * Hidden suite runs only after patch seal, in a separate stage/result set.
     * Never label hidden outcomes as F2P/P2P/P2F/F2F — post-only pass rate for scoring.
     * Fixture contents are staged outside the agent-visible workspace and only attached
     * for this stage (Docker mount or local path), then cleaned up.
     */
    private void runHiddenPost(Evaluation evaluation, JsonNode task) {
        if (evaluationRepository.findTestRun(evaluation.id(), TestPhase.HIDDEN_POST).isPresent()) {
            return;
        }
        JsonNode hidden = findSuite(task, "HIDDEN");
        if (hidden == null || hidden.isMissingNode() || hidden.isNull()
                || (!hidden.has("fixtureContent") && !hidden.has("cases") && !hidden.has("id"))) {
            // No hidden suite configured — record empty successful stage (pass-rate stubbed at score time).
            return;
        }

        // Ensure patch is sealed before hidden execution (defense in depth).
        PatchArtifact patch = evaluationRepository.findPatch(evaluation.id()).orElse(null);
        if (patch == null || !patch.sealed()) {
            throw new ApiException(
                    org.springframework.http.HttpStatus.CONFLICT,
                    FailureCode.INTERNAL.name(),
                    "Hidden suite requires a sealed patch");
        }

        Path workspace = workspaceProvisioner.requireExisting(evaluation.workspaceUri());
        // Separate clone so hidden stage never mutates the agent-visible tree unexpectedly.
        Path hiddenWorkspace = workspaceProvisioner.cleanClone(
                workspace, evaluation.orgId(), evaluation.id(), "hidden-run");
        Path hiddenFixtures = workspaceProvisioner.stageHiddenFixtures(
                evaluation.orgId(),
                evaluation.id(),
                hidden.path("fixtureContent").asText("{}"));

        ObjectNode containerMeta = objectMapper.createObjectNode();
        containerMeta.put("runner", "suite-v1");
        containerMeta.put("phase", "HIDDEN_POST");
        containerMeta.put("agentVisible", false);
        containerMeta.put("stage", ExecutionStage.HIDDEN_TESTS.name());
        containerMeta.put("runtime", executionIsolatorService.activeIsolator().runtimeName());
        containerMeta.put("hiddenFixturesSeparate", true);
        containerMeta.put("dockerSockMounted", false);
        containerMeta.put("privileged", false);

        UUID suiteId = hidden.hasNonNull("id") ? UUID.fromString(hidden.get("id").asText()) : null;
        TestRun run = new TestRun(
                UUID.randomUUID(), evaluation.id(), evaluation.orgId(), TestPhase.HIDDEN_POST, suiteId,
                "RUNNING", Instant.now(), null, writeJson(containerMeta));
        evaluationRepository.insertTestRun(run);

        try {
            int repeatRuns = resolveFlakyRepeats(task);
            FlakyDetectionService.FlakyAwareResult flakyAware = flakyDetectionService.runWithFlakyDetection(
                    evaluation.id(),
                    evaluation.orgId(),
                    run.id(),
                    TestPhase.HIDDEN_POST,
                    repeatRuns,
                    () -> suiteExecutionService.run(hiddenWorkspace, hidden, FixtureSuiteRunner.Phase.POST)
            );

            Map<String, CaseCategory> categories = classificationEngine.categoriesFromFixtureCases(
                    suiteExecutionService.listCaseNodes(hidden), true);
            List<TransitionClassificationEngine.ClassifiedCase> classified =
                    classificationEngine.classifyHiddenPostOnly(flakyAware.canonicalOutcomes(), categories);

            for (TransitionClassificationEngine.ClassifiedCase outcome : classified) {
                boolean flaky = flakyAware.flakyCaseKeys().contains(outcome.caseKey());
                String message = SecretRedactor.redact(outcome.message());
                evaluationRepository.insertTestCaseResult(new TestCaseResult(
                        UUID.randomUUID(), run.id(), evaluation.orgId(), outcome.caseKey(),
                        outcome.status(), outcome.durationMs(),
                        null, // never F2P/P2P/P2F/F2F on hidden
                        message == null ? "" : message, null,
                        outcome.category(), flaky, flakyAware.attemptCount(),
                        "{\"hidden\":true,\"neverF2P\":true}"));
            }
            evaluationRepository.finishTestRun(run.id(), "SUCCEEDED", Instant.now());
        } finally {
            // Remove hidden fixtures + hidden-run clone only; keep baseline/patched for quality.
            try {
                workspaceProvisioner.cleanupHiddenStaging(evaluation.orgId(), evaluation.id());
                artifactRetentionService.cleanupEvaluationWorkspace(hiddenFixtures);
            } catch (Exception cleanupEx) {
                log.warn("Hidden stage cleanup warning: {}", SecretRedactor.sanitizeError(cleanupEx.getMessage()));
            }
            executionIsolatorService.cleanupEvaluation(evaluation.id());
        }
    }

    /**
     * Checkstyle + SpotBugs + PMD (XML or builtin heuristics). Separates new vs pre-existing.
     * Sonar remains an optional stub and does not invent scores.
     */
    private void analyzeQuality(Evaluation evaluation, JsonNode task) {
        if (!evaluationRepository.findQualityReports(evaluation.id()).isEmpty()) {
            return;
        }
        Path patched = workspaceProvisioner.requireExisting(evaluation.workspaceUri());
        Path baseline = workspaceProvisioner.baselineWorkspace(evaluation.orgId(), evaluation.id());
        if (!Files.isDirectory(baseline) || baseline.equals(patched)) {
            // Fall back: re-provision a pristine baseline tree from the task snapshot.
            baseline = workspaceProvisioner.provision(evaluation.orgId(), evaluation.id(), task);
            if (baseline.equals(patched)) {
                baseline = workspaceProvisioner.cleanClone(
                        patched, evaluation.orgId(), evaluation.id(), "baseline-quality");
            }
        }

        QualityAnalysisService.AggregatedQuality aggregated =
                qualityAnalysisService.analyze(baseline, patched);
        String metricsJson = artifactRetentionService.truncateForStorage(aggregated.metricsJson());

        evaluationRepository.insertQualityReport(new QualityReport(
                UUID.randomUUID(),
                evaluation.id(),
                evaluation.orgId(),
                aggregated.toolLabel(),
                metricsJson,
                aggregated.normalizedScore(),
                Instant.now()
        ));
    }

    private String writeJson(Object node) {
        try {
            return objectMapper.writeValueAsString(node);
        } catch (Exception ex) {
            return "{}";
        }
    }

    private int resolveFlakyRepeats(JsonNode task) {
        int fromConfig = task.path("evalConfig").path("flakyRepeatRuns").asInt(-1);
        if (fromConfig > 0) {
            return fromConfig;
        }
        return evaluationRepository.findScoringProfile(ScoringProfile.DEFAULT_KEY)
                .map(ScoringProfile::flakyRepeatRuns)
                .orElse(1);
    }

    private void storePatch(Evaluation evaluation, String patchText, int maxBytes) throws Exception {
        List<String> allowed = allowedPaths(objectMapper.readTree(evaluation.snapshotJson()).path("task"));
        var validated = patchValidator.validate(patchText, maxBytes, allowed);
        byte[] bytes = validated.text().getBytes(StandardCharsets.UTF_8);
        String digest = sha256(bytes);
        String key = "orgs/%s/evaluations/%s/patches/%s.diff".formatted(
                evaluation.orgId(), evaluation.id(), digest);
        String uri = artifactStorage.store(key, new ByteArrayInputStream(bytes), bytes.length, "text/x-diff");
        evaluationRepository.insertPatch(new PatchArtifact(
                UUID.randomUUID(),
                evaluation.id(),
                evaluation.orgId(),
                digest,
                uri,
                "unified_diff",
                bytes.length,
                Instant.now(),
                true
        ));
    }

    private String readPatch(PatchArtifact patch) throws Exception {
        try (var in = artifactStorage.open(patch.storageUri())
                .orElseThrow(() -> new ApiException(
                        org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR,
                        FailureCode.RUNNER_ERROR.name(),
                        "Patch artifact missing"))) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private String buildVisibleAgentContext(JsonNode task) throws Exception {
        // Defense in depth: never include hidden suite bodies in provider prompts.
        ObjectNode visible = objectMapper.createObjectNode();
        visible.put("slug", task.path("slug").asText(null));
        visible.put("title", task.path("title").asText(null));
        visible.put("prompt", task.path("prompt").asText(""));
        visible.put("acceptanceCriteria", task.path("acceptanceCriteria").asText(null));
        visible.put("languageTags", task.path("languageTags"));
        JsonNode suites = task.path("suites");
        if (suites.isArray()) {
            var visibleSuites = objectMapper.createArrayNode();
            for (JsonNode suite : suites) {
                if ("VISIBLE".equalsIgnoreCase(suite.path("visibility").asText())) {
                    ObjectNode suiteNode = objectMapper.createObjectNode();
                    suiteNode.put("visibility", "VISIBLE");
                    suiteNode.put("framework", suite.path("framework").asText(null));
                    suiteNode.put("caseCount", suite.path("caseCount").asInt(0));
                    if (suite.has("fixtureContent")) {
                        suiteNode.put("fixtureContent", suite.path("fixtureContent").asText(""));
                    }
                    visibleSuites.add(suiteNode);
                }
            }
            visible.set("visibleSuites", visibleSuites);
        }
        return objectMapper.writeValueAsString(visible);
    }

    private void handleStageFailure(Evaluation evaluation, EvaluationCommand command, String code, String message) {
        evaluationRepository.completeStageAttempt(
                evaluation.id(), command.targetState(), command.attempt(), "FAILED", code, sanitize(message));
        evaluationRepository.markMessageProcessed(command.messageId(), evaluation.id(), evaluation.orgId());

        FailureCode failureCode;
        try {
            failureCode = FailureCode.valueOf(code);
        } catch (Exception ex) {
            if (code != null && (code.startsWith("AGENT_") || "CODING_CLI_NOT_IMPLEMENTED".equals(code))) {
                failureCode = "AGENT_TIMEOUT".equals(code) ? FailureCode.AGENT_TIMEOUT : FailureCode.AGENT_ERROR;
            } else {
                failureCode = FailureCode.INTERNAL;
            }
        }

        if (command.attempt() < maxAttempts
                && failureCode.failureClass() == FailureClass.INFRASTRUCTURE) {
            int nextAttempt = command.attempt() + 1;
            evaluationRepository.updateLifecycle(
                    evaluation.orgId(),
                    evaluation.id(),
                    evaluation.state(),
                    nextAttempt,
                    null, null, null,
                    evaluation.workspaceUri(),
                    evaluation.cancelRequested(),
                    evaluation.startedAt(),
                    null
            );
            String retryId = "eval-" + evaluation.id() + "-" + command.targetState() + "-" + nextAttempt + "-" + UUID.randomUUID();
            enqueue(evaluation, command.targetState(), nextAttempt, retryId);
            return;
        }

        Evaluation fresh = evaluationRepository.findById(evaluation.id()).orElse(evaluation);
        evaluationService.transitionTo(
                fresh,
                EvaluationState.FAILED,
                "Stage failed: " + command.targetState(),
                "worker",
                failureCode.failureClass(),
                failureCode.name(),
                sanitize(message)
        );
    }

    private void enqueue(Evaluation evaluation, EvaluationState target, int attempt, String messageId) {
        String payload;
        try {
            payload = objectMapper.writeValueAsString(
                    new EvaluationCommand(messageId, evaluation.id(), evaluation.orgId(), target, attempt));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
        evaluationRepository.insertOutbox(evaluation.orgId(), evaluation.id(), "evaluation.advance", payload);
    }

    private static JsonNode findSuite(JsonNode task, String visibility) {
        JsonNode suites = task.path("suites");
        if (suites.isArray()) {
            for (JsonNode suite : suites) {
                if (visibility.equalsIgnoreCase(suite.path("visibility").asText())) {
                    return suite;
                }
            }
        }
        if ("VISIBLE".equalsIgnoreCase(visibility)) {
            return task.path("visibleSuite");
        }
        if ("HIDDEN".equalsIgnoreCase(visibility)) {
            return task.path("hiddenSuite");
        }
        return objectMapperMissing();
    }

    private static JsonNode objectMapperMissing() {
        return com.fasterxml.jackson.databind.node.MissingNode.getInstance();
    }

    private static List<String> allowedPaths(JsonNode task) {
        List<String> allowed = new ArrayList<>();
        JsonNode config = task.path("evalConfig").path("allowedPatchPaths");
        if (config.isArray()) {
            config.forEach(n -> allowed.add(n.asText()));
        }
        JsonNode fixture = null;
        JsonNode suites = task.path("suites");
        if (suites.isArray()) {
            for (JsonNode suite : suites) {
                if ("VISIBLE".equalsIgnoreCase(suite.path("visibility").asText())) {
                    try {
                        String content = suite.path("fixtureContent").asText("");
                        if (content.trim().startsWith("{")) {
                            fixture = new ObjectMapper().readTree(content);
                        }
                    } catch (Exception ignored) {
                        // ignore
                    }
                }
            }
        }
        if (fixture != null && fixture.path("allowedPaths").isArray()) {
            fixture.path("allowedPaths").forEach(n -> allowed.add(n.asText()));
        }
        return allowed;
    }

    private static String inferRole(String caseKey) {
        if (caseKey == null) {
            return null;
        }
        String lower = caseKey.toLowerCase();
        if (lower.contains("target")) {
            return "target";
        }
        if (lower.contains("edge")) {
            return "edge";
        }
        if (lower.contains("control")) {
            return "control";
        }
        return null;
    }

    private static EvaluationState predecessor(EvaluationState target) {
        return switch (target) {
            case PREPARING_REPO -> EvaluationState.QUEUED;
            case RUNNING_BASELINE -> EvaluationState.PREPARING_REPO;
            case INVOKING_AGENT -> EvaluationState.RUNNING_BASELINE;
            case COLLECTING_PATCH -> EvaluationState.INVOKING_AGENT;
            case VALIDATING_PATCH -> EvaluationState.COLLECTING_PATCH;
            case APPLYING_PATCH -> EvaluationState.VALIDATING_PATCH;
            case RUNNING_VISIBLE_TESTS -> EvaluationState.APPLYING_PATCH;
            case RUNNING_HIDDEN_TESTS -> EvaluationState.RUNNING_VISIBLE_TESTS;
            case ANALYZING_QUALITY -> EvaluationState.RUNNING_HIDDEN_TESTS;
            case SCORING -> EvaluationState.ANALYZING_QUALITY;
            case COMPLETED -> EvaluationState.SCORING;
            default -> throw new IllegalArgumentException("No predecessor for " + target);
        };
    }

    private static List<String> activeNonQueued() {
        List<String> states = new ArrayList<>();
        for (EvaluationState state : EvaluationState.values()) {
            if (state.isActive() && state != EvaluationState.QUEUED) {
                states.add(state.name());
            }
        }
        return states;
    }

    private static String sha256(byte[] bytes) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return HexFormat.of().formatHex(digest.digest(bytes));
    }

    private static String sanitize(String message) {
        return SecretRedactor.sanitizeError(message);
    }
}
