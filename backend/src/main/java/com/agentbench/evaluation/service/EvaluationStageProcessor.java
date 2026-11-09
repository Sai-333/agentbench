package com.agentbench.evaluation.service;

import com.agentbench.agents.domain.Agent;
import com.agentbench.agents.infra.AgentRepository;
import com.agentbench.agents.service.FakeAgentAdapter;
import com.agentbench.evaluation.domain.Evaluation;
import com.agentbench.evaluation.domain.EvaluationState;
import com.agentbench.evaluation.domain.EvaluationStateMachine;
import com.agentbench.evaluation.domain.FailureClass;
import com.agentbench.evaluation.domain.FailureCode;
import com.agentbench.evaluation.domain.OrgEvaluationLimits;
import com.agentbench.evaluation.domain.PatchArtifact;
import com.agentbench.evaluation.domain.TestCaseResult;
import com.agentbench.evaluation.domain.TestCaseStatus;
import com.agentbench.evaluation.domain.TestPhase;
import com.agentbench.evaluation.domain.TestRun;
import com.agentbench.evaluation.infra.EvaluationRepository;
import com.agentbench.execution.service.FixtureSuiteRunner;
import com.agentbench.execution.service.PatchValidator;
import com.agentbench.execution.service.WorkspaceProvisioner;
import com.agentbench.platform.error.ApiException;
import com.agentbench.platform.infra.storage.ArtifactStorage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
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
    private final FixtureSuiteRunner fixtureSuiteRunner;
    private final PatchValidator patchValidator;
    private final FakeAgentAdapter fakeAgentAdapter;
    private final ArtifactStorage artifactStorage;
    private final ObjectMapper objectMapper;
    private final int maxAttempts;

    public EvaluationStageProcessor(
            EvaluationRepository evaluationRepository,
            EvaluationService evaluationService,
            AgentRepository agentRepository,
            WorkspaceProvisioner workspaceProvisioner,
            FixtureSuiteRunner fixtureSuiteRunner,
            PatchValidator patchValidator,
            FakeAgentAdapter fakeAgentAdapter,
            ArtifactStorage artifactStorage,
            ObjectMapper objectMapper,
            @Value("${agentbench.evaluation.max-stage-attempts:5}") int maxAttempts) {
        this.evaluationRepository = evaluationRepository;
        this.evaluationService = evaluationService;
        this.agentRepository = agentRepository;
        this.workspaceProvisioner = workspaceProvisioner;
        this.fixtureSuiteRunner = fixtureSuiteRunner;
        this.patchValidator = patchValidator;
        this.fakeAgentAdapter = fakeAgentAdapter;
        this.artifactStorage = artifactStorage;
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

        // Concurrency gate when leaving QUEUED
        if (evaluation.state() == EvaluationState.QUEUED && command.targetState() == EvaluationState.PREPARING_REPO) {
            OrgEvaluationLimits limits = evaluationRepository.getOrDefaultLimits(evaluation.orgId());
            int active = evaluationRepository.countByStates(evaluation.orgId(), activeNonQueued());
            if (active >= limits.maxConcurrentEvaluations()) {
                // Re-queue later via outbox without failing.
                String retryMessageId = "eval-" + evaluation.id() + "-QUEUED-retry-" + UUID.randomUUID();
                enqueue(evaluation, EvaluationState.PREPARING_REPO, evaluation.attempt(), retryMessageId);
                evaluationRepository.markMessageProcessed(command.messageId(), evaluation.id(), evaluation.orgId());
                return;
            }
        }

        EvaluationState expectedCurrent = predecessor(command.targetState());
        boolean alreadyInTarget = evaluation.state() == command.targetState();
        if (evaluation.state() != expectedCurrent && !alreadyInTarget) {
            // Stale or out-of-order message.
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
                // Stub agent stage (full providers in M5): collect patch via FakeAgentAdapter.
                if (evaluationRepository.findPatch(evaluation.id()).isPresent()) {
                    return;
                }
                Agent agent = agentRepository.find(evaluation.orgId(), evaluation.agentId())
                        .orElseThrow(() -> ApiException.notFound("Agent not found"));
                String patch = fakeAgentAdapter.collectPatch(agent, task);
                storePatch(evaluation, patch, limits.maxPatchBytes());
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
            case RUNNING_HIDDEN_TESTS, ANALYZING_QUALITY, SCORING, COMPLETED -> {
                // Scaffolded stages for later milestones — succeed as no-op hooks.
                // COMPLETED is a terminal transition with no side effects.
            }
            default -> throw new IllegalStateException("Unexpected stage " + command.targetState());
        }
    }

    private void runBaseline(Evaluation evaluation, JsonNode task) {
        if (evaluationRepository.findTestRun(evaluation.id(), TestPhase.BASELINE).isPresent()) {
            return; // idempotent
        }
        Path workspace = workspaceProvisioner.requireExisting(evaluation.workspaceUri());
        JsonNode visible = findSuite(task, "VISIBLE");
        JsonNode fixture = fixtureSuiteRunner.parseFixtureContent(visible.path("fixtureContent").asText(""));
        // Prefer structured fixture; merge expected outcomes.
        var suiteNode = objectMapper.createObjectNode();
        suiteNode.set("cases", fixture.path("cases"));
        suiteNode.set("expectedOutcomes", visible.path("expectedOutcomes"));
        if (fixture.has("workspace") && evaluation.workspaceUri() != null) {
            // workspace already provisioned from task.fixtureWorkspace / suite content
        }

        UUID suiteId = visible.hasNonNull("id") ? UUID.fromString(visible.get("id").asText()) : null;
        Instant started = Instant.now();
        TestRun run = new TestRun(
                UUID.randomUUID(), evaluation.id(), evaluation.orgId(), TestPhase.BASELINE, suiteId,
                "RUNNING", started, null, "{\"runner\":\"fixture-v1\"}");
        evaluationRepository.insertTestRun(run);

        List<FixtureSuiteRunner.CaseOutcome> outcomes =
                fixtureSuiteRunner.run(workspace, suiteNode.has("cases") && !suiteNode.get("cases").isMissingNode()
                        ? suiteNode
                        : visible, FixtureSuiteRunner.Phase.BASELINE);

        boolean targetFailed = false;
        for (FixtureSuiteRunner.CaseOutcome outcome : outcomes) {
            evaluationRepository.insertTestCaseResult(new TestCaseResult(
                    UUID.randomUUID(), run.id(), evaluation.orgId(), outcome.caseKey(),
                    outcome.status(), outcome.durationMs(), null, outcome.message(), null));
            if (outcome.caseKey().toLowerCase().contains("target") && outcome.status() == TestCaseStatus.FAIL) {
                targetFailed = true;
            }
            if ("target".equalsIgnoreCase(outcome.caseKey()) && outcome.status() == TestCaseStatus.FAIL) {
                targetFailed = true;
            }
        }
        // If any FAIL exists, treat baseline as reproducible for bug-fixing fixtures.
        boolean anyFail = outcomes.stream().anyMatch(o -> o.status() == TestCaseStatus.FAIL);
        evaluationRepository.finishTestRun(run.id(), "SUCCEEDED", Instant.now());

        JsonNode requireTargetFail = task.path("evalConfig").path("requireTargetFailAtBaseline");
        boolean require = requireTargetFail.isMissingNode() || requireTargetFail.asBoolean(true);
        if (require && !anyFail && !targetFailed && !outcomes.isEmpty()) {
            // Soft check: only fail when fixture explicitly marks role=target without FAIL.
            boolean hasTargetRole = false;
            JsonNode cases = suiteNode.path("cases");
            if (cases.isArray()) {
                for (JsonNode c : cases) {
                    if ("target".equalsIgnoreCase(c.path("role").asText())
                            || c.path("key").asText().toLowerCase().contains("target")) {
                        hasTargetRole = true;
                        break;
                    }
                }
            }
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
        JsonNode fixture = fixtureSuiteRunner.parseFixtureContent(visible.path("fixtureContent").asText(""));
        var suiteNode = objectMapper.createObjectNode();
        suiteNode.set("cases", fixture.path("cases"));
        suiteNode.set("expectedOutcomes", visible.path("expectedOutcomes"));

        UUID suiteId = visible.hasNonNull("id") ? UUID.fromString(visible.get("id").asText()) : null;
        TestRun run = new TestRun(
                UUID.randomUUID(), evaluation.id(), evaluation.orgId(), TestPhase.VISIBLE_POST, suiteId,
                "RUNNING", Instant.now(), null, "{\"runner\":\"fixture-v1\"}");
        evaluationRepository.insertTestRun(run);

        List<FixtureSuiteRunner.CaseOutcome> post =
                fixtureSuiteRunner.run(workspace, suiteNode.path("cases").isMissingNode() ? visible : suiteNode,
                        FixtureSuiteRunner.Phase.POST);

        TestRun baseline = evaluationRepository.findTestRun(evaluation.id(), TestPhase.BASELINE).orElse(null);
        List<TestCaseResult> baselineResults = baseline == null
                ? List.of()
                : evaluationRepository.listTestCaseResults(baseline.id());

        for (FixtureSuiteRunner.CaseOutcome outcome : post) {
            String transition = null;
            TestCaseStatus base = baselineResults.stream()
                    .filter(r -> r.caseKey().equals(outcome.caseKey()))
                    .map(TestCaseResult::status)
                    .findFirst()
                    .orElse(null);
            if (base != null
                    && (base == TestCaseStatus.PASS || base == TestCaseStatus.FAIL)
                    && (outcome.status() == TestCaseStatus.PASS || outcome.status() == TestCaseStatus.FAIL)) {
                transition = classify(base, outcome.status());
            }
            evaluationRepository.insertTestCaseResult(new TestCaseResult(
                    UUID.randomUUID(), run.id(), evaluation.orgId(), outcome.caseKey(),
                    outcome.status(), outcome.durationMs(), transition, outcome.message(), null));
        }
        evaluationRepository.finishTestRun(run.id(), "SUCCEEDED", Instant.now());
    }

    private static String classify(TestCaseStatus baseline, TestCaseStatus post) {
        if (baseline == TestCaseStatus.FAIL && post == TestCaseStatus.PASS) {
            return "F2P";
        }
        if (baseline == TestCaseStatus.PASS && post == TestCaseStatus.PASS) {
            return "P2P";
        }
        if (baseline == TestCaseStatus.PASS && post == TestCaseStatus.FAIL) {
            return "P2F";
        }
        return "F2F";
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

    private void handleStageFailure(Evaluation evaluation, EvaluationCommand command, String code, String message) {
        evaluationRepository.completeStageAttempt(
                evaluation.id(), command.targetState(), command.attempt(), "FAILED", code, sanitize(message));
        evaluationRepository.markMessageProcessed(command.messageId(), evaluation.id(), evaluation.orgId());

        FailureCode failureCode;
        try {
            failureCode = FailureCode.valueOf(code);
        } catch (Exception ex) {
            failureCode = FailureCode.INTERNAL;
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
        return task.path("visibleSuite");
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
                        // parsed later
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
        if (message == null) {
            return "";
        }
        String cleaned = message.replaceAll("(/[A-Za-z0-9._-]+)+", "[path]");
        return cleaned.substring(0, Math.min(cleaned.length(), 400));
    }
}
