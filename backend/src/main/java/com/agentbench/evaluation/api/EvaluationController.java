package com.agentbench.evaluation.api;

import com.agentbench.evaluation.api.dto.EvaluationDtos;
import com.agentbench.evaluation.service.EvaluationService;
import com.agentbench.platform.api.ApiPaths;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping(ApiPaths.ORG_BASE + "/evaluations")
@Tag(name = "Evaluations", description = "Evaluation orchestration lifecycle (Milestone 4)")
@SecurityRequirement(name = "bearerAuth")
public class EvaluationController {

    private final EvaluationService evaluationService;

    public EvaluationController(EvaluationService evaluationService) {
        this.evaluationService = evaluationService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create / enqueue an evaluation")
    public EvaluationDtos.EvaluationResponse create(
            @PathVariable UUID orgId,
            @Valid @RequestBody EvaluationDtos.CreateEvaluationRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        return evaluationService.create(orgId, request, idempotencyKey);
    }

    @GetMapping
    @Operation(summary = "List evaluations")
    public List<EvaluationDtos.EvaluationResponse> list(
            @PathVariable UUID orgId,
            @RequestParam(required = false) String state,
            @RequestParam(required = false) UUID agentId,
            @RequestParam(required = false) UUID taskId,
            @RequestParam(required = false) Integer limit) {
        return evaluationService.list(orgId, state, agentId, taskId, limit);
    }

    @GetMapping("/{evaluationId}")
    @Operation(summary = "Get evaluation detail with timeline and test results")
    public EvaluationDtos.EvaluationDetailResponse get(
            @PathVariable UUID orgId,
            @PathVariable UUID evaluationId) {
        return evaluationService.get(orgId, evaluationId);
    }

    @GetMapping("/{evaluationId}/transitions")
    @Operation(summary = "Evaluation state audit trail")
    public List<EvaluationDtos.TransitionResponse> transitions(
            @PathVariable UUID orgId,
            @PathVariable UUID evaluationId) {
        return evaluationService.listTransitions(orgId, evaluationId);
    }

    @PostMapping("/{evaluationId}/cancel")
    @Operation(summary = "Cancel an evaluation")
    public EvaluationDtos.EvaluationResponse cancel(
            @PathVariable UUID orgId,
            @PathVariable UUID evaluationId) {
        return evaluationService.cancel(orgId, evaluationId);
    }
}
