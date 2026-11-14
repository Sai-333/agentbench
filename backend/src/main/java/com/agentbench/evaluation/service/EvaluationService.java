package com.agentbench.evaluation.service;

import com.agentbench.agents.api.dto.AgentDtos;
import com.agentbench.agents.domain.Agent;
import com.agentbench.agents.domain.AgentInvocation;
import com.agentbench.agents.infra.AgentRepository;
import com.agentbench.agents.service.AgentInvocationService;
import com.agentbench.agents.service.AgentService;
import com.agentbench.benchmarks.domain.BenchmarkStatus;
import com.agentbench.benchmarks.domain.BenchmarkVersion;
import com.agentbench.benchmarks.domain.Task;
import com.agentbench.benchmarks.infra.BenchmarkRepository;
import com.agentbench.evaluation.api.dto.EvaluationDtos;
import com.agentbench.evaluation.domain.Evaluation;
import com.agentbench.evaluation.domain.EvaluationState;
import com.agentbench.evaluation.domain.EvaluationStateMachine;
import com.agentbench.evaluation.domain.EvaluationTransition;
import com.agentbench.evaluation.domain.FailureClass;
import com.agentbench.evaluation.domain.FailureCode;
import com.agentbench.evaluation.domain.OrgEvaluationLimits;
import com.agentbench.evaluation.domain.PatchArtifact;
import com.agentbench.evaluation.domain.QualityReport;
import com.agentbench.evaluation.domain.ScoreMetric;
import com.agentbench.evaluation.domain.TestCaseResult;
import com.agentbench.evaluation.domain.TestRun;
import com.agentbench.evaluation.infra.EvaluationRepository;
import com.agentbench.identity.domain.OrgRole;
import com.agentbench.identity.security.OrgAccessService;
import com.agentbench.identity.security.UserPrincipal;
import com.agentbench.platform.error.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class EvaluationService {

    private final EvaluationRepository evaluationRepository;
    private final BenchmarkRepository benchmarkRepository;
    private final AgentRepository agentRepository;
    private final AgentService agentService;
    private final AgentInvocationService agentInvocationService;
    private final OrgAccessService orgAccessService;
    private final ObjectMapper objectMapper;

    public EvaluationService(
            EvaluationRepository evaluationRepository,
            BenchmarkRepository benchmarkRepository,
            AgentRepository agentRepository,
            AgentService agentService,
            AgentInvocationService agentInvocationService,
            OrgAccessService orgAccessService,
            ObjectMapper objectMapper) {
        this.evaluationRepository = evaluationRepository;
        this.benchmarkRepository = benchmarkRepository;
        this.agentRepository = agentRepository;
        this.agentService = agentService;
        this.agentInvocationService = agentInvocationService;
        this.orgAccessService = orgAccessService;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public EvaluationDtos.EvaluationResponse create(
            UUID orgId,
            EvaluationDtos.CreateEvaluationRequest request,
            String idempotencyKey) {
        orgAccessService.requireMembership(orgId, OrgRole.DEVELOPER);
        UserPrincipal principal = orgAccessService.requirePrincipal();

        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            var existing = evaluationRepository.findByIdempotencyKey(orgId, idempotencyKey.trim());
            if (existing.isPresent()) {
                return toResponse(existing.get());
            }
        }

        OrgEvaluationLimits limits = evaluationRepository.getOrDefaultLimits(orgId);
        int queued = evaluationRepository.countByStates(orgId, List.of(EvaluationState.QUEUED.name()));
        if (queued >= limits.maxQueuedEvaluations()) {
            throw ApiException.conflict("Organization queued evaluation limit reached (" + limits.maxQueuedEvaluations() + ")");
        }
        int active = evaluationRepository.countByStates(orgId, activeStateNames());
        if (active >= limits.maxConcurrentEvaluations()) {
            // Still allow queueing up to maxQueued; only block when queue is also saturated above.
            // Concurrent cap applies to non-QUEUED active work; queued can fill separately.
        }

        BenchmarkVersion version = benchmarkRepository.findVersion(orgId, request.benchmarkVersionId())
                .orElseThrow(() -> ApiException.notFound("Benchmark version not found"));
        if (version.status() != BenchmarkStatus.PUBLISHED) {
            throw ApiException.validation("Evaluations require a PUBLISHED benchmark version");
        }
        if (version.immutableSnapshotJson() == null || version.immutableSnapshotJson().isBlank()) {
            throw ApiException.conflict("Published version is missing immutable snapshot");
        }

        Task task = benchmarkRepository.findTask(orgId, request.taskId())
                .orElseThrow(() -> ApiException.notFound("Task not found"));
        if (!task.benchmarkVersionId().equals(version.id())) {
            throw ApiException.validation("Task does not belong to the given benchmark version");
        }

        Agent agent = request.agentId() == null
                ? agentService.ensureDefaultGrokAgent(orgId)
                : agentRepository.find(orgId, request.agentId())
                        .orElseThrow(() -> ApiException.notFound("Agent not found"));
        if (!agent.enabled()) {
            throw ApiException.validation("Agent is disabled");
        }

        String evaluationSnapshot = buildEvaluationSnapshot(version.immutableSnapshotJson(), task.id());
        Instant now = Instant.now();
        UUID evaluationId = UUID.randomUUID();
        Evaluation evaluation = new Evaluation(
                evaluationId,
                orgId,
                version.id(),
                task.id(),
                agent.id(),
                EvaluationState.QUEUED,
                1,
                null,
                null,
                null,
                idempotencyKey == null || idempotencyKey.isBlank() ? null : idempotencyKey.trim(),
                evaluationSnapshot,
                null,
                false,
                principal.userId(),
                now,
                now,
                null,
                null
        );
        EvaluationStateMachine.assertTransition(null, EvaluationState.QUEUED);
        evaluationRepository.insert(evaluation);
        evaluationRepository.insertTransition(
                evaluationId, orgId, null, EvaluationState.QUEUED, "Evaluation created", principal.userId().toString(), 1);

        String messageId = "eval-" + evaluationId + "-QUEUED-1-" + UUID.randomUUID();
        String payload = writeJson(new EvaluationCommand(messageId, evaluationId, orgId, EvaluationState.PREPARING_REPO, 1));
        evaluationRepository.insertOutbox(orgId, evaluationId, "evaluation.advance", payload);

        return toResponse(evaluation);
    }

    @Transactional(readOnly = true)
    public List<EvaluationDtos.EvaluationResponse> list(
            UUID orgId, String state, UUID agentId, UUID taskId, Integer limit) {
        orgAccessService.requireMembership(orgId, OrgRole.VIEWER);
        return evaluationRepository.list(orgId, state, agentId, taskId, limit == null ? 50 : limit)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public EvaluationDtos.EvaluationDetailResponse get(UUID orgId, UUID evaluationId) {
        orgAccessService.requireMembership(orgId, OrgRole.VIEWER);
        Evaluation evaluation = require(orgId, evaluationId);
        List<EvaluationTransition> transitions = evaluationRepository.listTransitions(orgId, evaluationId);
        List<EvaluationDtos.TestRunResponse> runResponses = buildTestRunResponses(evaluationId);
        PatchArtifact patch = evaluationRepository.findPatch(evaluationId).orElse(null);
        List<AgentDtos.AgentInvocationResponse> invocations = agentInvocationService
                .listForEvaluation(orgId, evaluationId)
                .stream()
                .map(this::toInvocationResponse)
                .toList();
        return new EvaluationDtos.EvaluationDetailResponse(
                toResponse(evaluation),
                transitions.stream().map(this::toTransition).toList(),
                runResponses,
                patch == null ? null : new EvaluationDtos.PatchResponse(
                        patch.id(), patch.contentDigest(), patch.format(), patch.byteSize(),
                        patch.collectedAt(), patch.sealed(), patch.purgedAt()),
                invocations,
                buildScoreResponse(evaluationId),
                buildQualityReports(evaluationId)
        );
    }

    @Transactional(readOnly = true)
    public EvaluationDtos.EvaluationReportResponse report(UUID orgId, UUID evaluationId) {
        orgAccessService.requireMembership(orgId, OrgRole.VIEWER);
        Evaluation evaluation = require(orgId, evaluationId);
        EvaluationDtos.ScoreResponse score = buildScoreResponse(evaluationId);
        List<EvaluationDtos.TestRunResponse> runs = buildTestRunResponses(evaluationId);
        List<EvaluationDtos.QualityReportResponse> qualityReports = buildQualityReports(evaluationId);

        Map<String, Integer> visibleTransitions = new LinkedHashMap<>();
        visibleTransitions.put("F2P", 0);
        visibleTransitions.put("P2P", 0);
        visibleTransitions.put("P2F", 0);
        visibleTransitions.put("F2F", 0);
        int hiddenTotal = 0;
        int hiddenPassed = 0;
        for (EvaluationDtos.TestRunResponse run : runs) {
            if ("VISIBLE_POST".equals(run.phase())) {
                for (EvaluationDtos.TestCaseResultResponse result : run.results()) {
                    if (result.transition() != null) {
                        visibleTransitions.merge(result.transition(), 1, Integer::sum);
                    }
                }
            }
            if ("HIDDEN_POST".equals(run.phase())) {
                for (EvaluationDtos.TestCaseResultResponse result : run.results()) {
                    hiddenTotal++;
                    if ("PASS".equals(result.status())) {
                        hiddenPassed++;
                    }
                }
            }
        }
        if (score != null && score.transitionSummary() != null && !score.transitionSummary().isEmpty()) {
            visibleTransitions.putAll(score.transitionSummary());
        }
        Double passRate = hiddenTotal == 0 ? null : (double) hiddenPassed / (double) hiddenTotal;
        return new EvaluationDtos.EvaluationReportResponse(
                toResponse(evaluation),
                score,
                visibleTransitions,
                new EvaluationDtos.HiddenSummary(hiddenTotal, hiddenPassed, passRate, true),
                runs,
                qualityReports
        );
    }

    private List<EvaluationDtos.QualityReportResponse> buildQualityReports(UUID evaluationId) {
        return evaluationRepository.findQualityReports(evaluationId).stream()
                .map(this::toQualityReport)
                .toList();
    }

    @SuppressWarnings("unchecked")
    private EvaluationDtos.QualityReportResponse toQualityReport(QualityReport report) {
        Map<String, Object> metrics = readObjectMap(report.metricsJson());
        boolean stub = true;
        int newCount = 0;
        int preCount = 0;
        List<EvaluationDtos.QualityToolSummaryResponse> tools = new ArrayList<>();
        Object stubObj = metrics.get("stub");
        if (stubObj instanceof Boolean b) {
            stub = b;
        }
        if (report.normalizedScore() != null) {
            stub = false;
        }
        Object newObj = metrics.get("newFindingCount");
        if (newObj instanceof Number n) {
            newCount = n.intValue();
        }
        Object preObj = metrics.get("preExistingFindingCount");
        if (preObj instanceof Number n) {
            preCount = n.intValue();
        }
        Object toolsObj = metrics.get("tools");
        if (toolsObj instanceof List<?> toolList) {
            for (Object item : toolList) {
                if (!(item instanceof Map<?, ?> map)) {
                    continue;
                }
                List<EvaluationDtos.QualityFindingResponse> samples = new ArrayList<>();
                Object sampleObj = map.get("findingsSample");
                if (sampleObj instanceof List<?> sampleList) {
                    for (Object sample : sampleList) {
                        if (!(sample instanceof Map<?, ?> sm)) {
                            continue;
                        }
                        samples.add(new EvaluationDtos.QualityFindingResponse(
                                stringVal(sm.get("ruleId")),
                                stringVal(sm.get("severity")),
                                stringVal(sm.get("filePath")),
                                intVal(sm.get("line")),
                                stringVal(sm.get("message")),
                                boolVal(sm.get("introduced"), true)
                        ));
                    }
                }
                tools.add(new EvaluationDtos.QualityToolSummaryResponse(
                        stringVal(map.get("tool")),
                        boolVal(map.get("stub"), false),
                        stringVal(map.get("notes")),
                        intVal(map.get("totalFindings")),
                        intVal(map.get("newFindings")),
                        intVal(map.get("preExistingFindings")),
                        samples
                ));
            }
        }
        return new EvaluationDtos.QualityReportResponse(
                report.id(),
                report.tool(),
                report.normalizedScore(),
                stub,
                newCount,
                preCount,
                tools,
                metrics,
                report.createdAt()
        );
    }

    private static String stringVal(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static int intVal(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        return 0;
    }

    private static boolean boolVal(Object value, boolean fallback) {
        if (value instanceof Boolean b) {
            return b;
        }
        return fallback;
    }

    private List<EvaluationDtos.TestRunResponse> buildTestRunResponses(UUID evaluationId) {
        List<TestRun> runs = evaluationRepository.listTestRuns(evaluationId);
        List<EvaluationDtos.TestRunResponse> runResponses = new ArrayList<>();
        for (TestRun run : runs) {
            List<TestCaseResult> results = evaluationRepository.listTestCaseResults(run.id());
            runResponses.add(new EvaluationDtos.TestRunResponse(
                    run.id(),
                    run.phase().name(),
                    run.status(),
                    run.startedAt(),
                    run.finishedAt(),
                    results.stream()
                            .map(r -> new EvaluationDtos.TestCaseResultResponse(
                                    r.caseKey(),
                                    r.status().name(),
                                    r.durationMs(),
                                    r.transition(),
                                    r.message(),
                                    r.category() == null ? null : r.category().name(),
                                    r.flaky(),
                                    FlakeQuarantineService.isQuarantinedMeta(r.metaJson()),
                                    r.attemptCount()))
                            .toList()
            ));
        }
        return runResponses;
    }

    private EvaluationDtos.ScoreResponse buildScoreResponse(UUID evaluationId) {
        return evaluationRepository.findScore(evaluationId).map(score -> {
            List<ScoreMetric> metrics = evaluationRepository.listScoreMetrics(score.id());
            return new EvaluationDtos.ScoreResponse(
                    score.id(),
                    score.scoreStatus().name(),
                    score.weightedTotal(),
                    score.profileKey(),
                    readIntMap(score.transitionSummaryJson()),
                    readObjectMap(score.rawSummaryJson()),
                    readStringList(score.warningsJson()),
                    metrics.stream()
                            .map(m -> new EvaluationDtos.ScoreMetricResponse(
                                    m.metricKey(),
                                    m.rawValue(),
                                    m.weight(),
                                    m.weightedValue(),
                                    readObjectMap(m.metaJson())))
                            .toList(),
                    score.computedAt()
            );
        }).orElse(null);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Integer> readIntMap(String json) {
        try {
            if (json == null || json.isBlank()) {
                return Map.of();
            }
            Map<String, Object> raw = objectMapper.readValue(json, Map.class);
            Map<String, Integer> out = new LinkedHashMap<>();
            for (Map.Entry<String, Object> entry : raw.entrySet()) {
                if (entry.getValue() instanceof Number number) {
                    out.put(entry.getKey(), number.intValue());
                }
            }
            return out;
        } catch (Exception ex) {
            return Map.of();
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readObjectMap(String json) {
        try {
            if (json == null || json.isBlank()) {
                return Map.of();
            }
            return objectMapper.readValue(json, Map.class);
        } catch (Exception ex) {
            return Map.of();
        }
    }

    @SuppressWarnings("unchecked")
    private List<String> readStringList(String json) {
        try {
            if (json == null || json.isBlank()) {
                return List.of();
            }
            return objectMapper.readValue(json, List.class);
        } catch (Exception ex) {
            return List.of();
        }
    }

    private AgentDtos.AgentInvocationResponse toInvocationResponse(AgentInvocation inv) {
        return new AgentDtos.AgentInvocationResponse(
                inv.id(),
                inv.evaluationId(),
                inv.agentId(),
                inv.taskId(),
                inv.provider(),
                inv.model(),
                inv.providerVersion(),
                inv.prompt(),
                inv.status(),
                inv.attempt(),
                inv.maxAttempts(),
                inv.startedAt(),
                inv.finishedAt(),
                inv.patchDigest(),
                inv.patchByteSize(),
                inv.promptTokens(),
                inv.completionTokens(),
                inv.totalTokens(),
                inv.estimatedCostUsd(),
                inv.errorCode(),
                inv.errorMessage(),
                readMeta(inv.metaJson()),
                inv.createdAt()
        );
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readMeta(String json) {
        try {
            if (json == null || json.isBlank()) {
                return Map.of();
            }
            return objectMapper.readValue(json, Map.class);
        } catch (Exception ex) {
            return Map.of();
        }
    }

    @Transactional(readOnly = true)
    public List<EvaluationDtos.TransitionResponse> listTransitions(UUID orgId, UUID evaluationId) {
        orgAccessService.requireMembership(orgId, OrgRole.VIEWER);
        require(orgId, evaluationId);
        return evaluationRepository.listTransitions(orgId, evaluationId).stream()
                .map(this::toTransition)
                .toList();
    }

    @Transactional
    public EvaluationDtos.EvaluationResponse cancel(UUID orgId, UUID evaluationId) {
        orgAccessService.requireMembership(orgId, OrgRole.DEVELOPER);
        UserPrincipal principal = orgAccessService.requirePrincipal();
        Evaluation evaluation = require(orgId, evaluationId);
        if (evaluation.state().isTerminal()) {
            throw ApiException.conflict("Evaluation already terminal: " + evaluation.state());
        }
        if (!evaluation.state().isCancelable()) {
            throw ApiException.conflict("Evaluation cannot be cancelled from " + evaluation.state());
        }
        evaluationRepository.markCancelRequested(orgId, evaluationId);
        if (evaluation.state() == EvaluationState.QUEUED) {
            transitionTo(
                    evaluation,
                    EvaluationState.CANCELLED,
                    "Cancelled by user",
                    principal.userId().toString(),
                    FailureClass.USER,
                    FailureCode.CANCELLED_BY_USER.name(),
                    "Cancelled by user"
            );
            return toResponse(require(orgId, evaluationId));
        }
        // In-flight: mark cancel_requested; worker honors between stages.
        return toResponse(require(orgId, evaluationId));
    }

    Evaluation require(UUID orgId, UUID evaluationId) {
        return evaluationRepository.find(orgId, evaluationId)
                .orElseThrow(() -> ApiException.notFound("Evaluation not found"));
    }

    public void transitionTo(
            Evaluation evaluation,
            EvaluationState to,
            String reason,
            String actor,
            FailureClass failureClass,
            String errorCode,
            String errorMessage) {
        EvaluationStateMachine.assertTransition(evaluation.state(), to);
        Instant now = Instant.now();
        Instant startedAt = evaluation.startedAt() == null && to == EvaluationState.PREPARING_REPO ? now : evaluation.startedAt();
        Instant completedAt = to.isTerminal() ? now : null;
        evaluationRepository.updateLifecycle(
                evaluation.orgId(),
                evaluation.id(),
                to,
                evaluation.attempt(),
                failureClass,
                errorCode,
                errorMessage,
                evaluation.workspaceUri(),
                evaluation.cancelRequested() || to == EvaluationState.CANCELLED,
                startedAt,
                completedAt
        );
        evaluationRepository.insertTransition(
                evaluation.id(),
                evaluation.orgId(),
                evaluation.state(),
                to,
                reason,
                actor,
                evaluation.attempt()
        );
    }

    private String buildEvaluationSnapshot(String versionSnapshotJson, UUID taskId) {
        try {
            JsonNode root = objectMapper.readTree(versionSnapshotJson);
            ObjectNode evalSnap = objectMapper.createObjectNode();
            evalSnap.put("schema", "agentbench.evaluation-snapshot.v1");
            evalSnap.set("benchmarkVersion", root);
            JsonNode tasks = root.path("tasks");
            JsonNode selected = null;
            if (tasks.isArray()) {
                for (JsonNode taskNode : tasks) {
                    if (taskId.toString().equals(taskNode.path("id").asText())) {
                        selected = taskNode;
                        break;
                    }
                }
            }
            if (selected == null) {
                throw ApiException.validation("Task missing from published version snapshot");
            }
            evalSnap.set("task", selected);
            return objectMapper.writeValueAsString(evalSnap);
        } catch (ApiException ex) {
            throw ex;
        } catch (Exception ex) {
            throw ApiException.conflict("Unable to materialize evaluation snapshot");
        }
    }

    private EvaluationDtos.EvaluationResponse toResponse(Evaluation evaluation) {
        return new EvaluationDtos.EvaluationResponse(
                evaluation.id(),
                evaluation.orgId(),
                evaluation.benchmarkVersionId(),
                evaluation.taskId(),
                evaluation.agentId(),
                evaluation.state().name(),
                evaluation.attempt(),
                evaluation.failureClass() == null ? null : evaluation.failureClass().name(),
                evaluation.errorCode(),
                evaluation.errorMessage(),
                evaluation.cancelRequested(),
                evaluation.createdBy(),
                evaluation.createdAt(),
                evaluation.updatedAt(),
                evaluation.startedAt(),
                evaluation.completedAt()
        );
    }

    private EvaluationDtos.TransitionResponse toTransition(EvaluationTransition transition) {
        return new EvaluationDtos.TransitionResponse(
                transition.id(),
                transition.fromState() == null ? null : transition.fromState().name(),
                transition.toState().name(),
                transition.reason(),
                transition.actor(),
                transition.attempt(),
                transition.createdAt()
        );
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static List<String> activeStateNames() {
        List<String> states = new ArrayList<>();
        for (EvaluationState state : EvaluationState.values()) {
            if (state.isActive() && state != EvaluationState.QUEUED) {
                states.add(state.name());
            }
        }
        return states;
    }
}
