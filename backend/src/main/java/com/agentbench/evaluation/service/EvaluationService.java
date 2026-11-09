package com.agentbench.evaluation.service;

import com.agentbench.agents.domain.Agent;
import com.agentbench.agents.infra.AgentRepository;
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
import java.util.List;
import java.util.UUID;

@Service
public class EvaluationService {

    private final EvaluationRepository evaluationRepository;
    private final BenchmarkRepository benchmarkRepository;
    private final AgentRepository agentRepository;
    private final OrgAccessService orgAccessService;
    private final ObjectMapper objectMapper;

    public EvaluationService(
            EvaluationRepository evaluationRepository,
            BenchmarkRepository benchmarkRepository,
            AgentRepository agentRepository,
            OrgAccessService orgAccessService,
            ObjectMapper objectMapper) {
        this.evaluationRepository = evaluationRepository;
        this.benchmarkRepository = benchmarkRepository;
        this.agentRepository = agentRepository;
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

        Agent agent = agentRepository.find(orgId, request.agentId())
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
                                    r.caseKey(), r.status().name(), r.durationMs(), r.transition(), r.message()))
                            .toList()
            ));
        }
        PatchArtifact patch = evaluationRepository.findPatch(evaluationId).orElse(null);
        return new EvaluationDtos.EvaluationDetailResponse(
                toResponse(evaluation),
                transitions.stream().map(this::toTransition).toList(),
                runResponses,
                patch == null ? null : new EvaluationDtos.PatchResponse(
                        patch.id(), patch.contentDigest(), patch.format(), patch.byteSize(),
                        patch.collectedAt(), patch.sealed())
        );
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
