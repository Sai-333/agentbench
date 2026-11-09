package com.agentbench.benchmarks.api;

import com.agentbench.benchmarks.api.dto.BenchmarkDtos;
import com.agentbench.benchmarks.service.BenchmarkService;
import com.agentbench.platform.api.ApiPaths;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping(ApiPaths.API_V1 + "/orgs/{orgId}")
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "Benchmarks", description = "Benchmark definitions, versions, tasks, and visible/hidden suites")
public class BenchmarkController {

    private final BenchmarkService benchmarkService;

    public BenchmarkController(BenchmarkService benchmarkService) {
        this.benchmarkService = benchmarkService;
    }

    @PostMapping(value = "/benchmarks", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create benchmark")
    public BenchmarkDtos.BenchmarkResponse createBenchmark(
            @PathVariable UUID orgId,
            @Valid @RequestBody BenchmarkDtos.CreateBenchmarkRequest request) {
        return benchmarkService.createBenchmark(orgId, request);
    }

    @GetMapping(value = "/benchmarks", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "List benchmarks in organization")
    public List<BenchmarkDtos.BenchmarkResponse> listBenchmarks(@PathVariable UUID orgId) {
        return benchmarkService.listBenchmarks(orgId);
    }

    @GetMapping(value = "/benchmarks/{benchmarkId}", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Get benchmark")
    public BenchmarkDtos.BenchmarkResponse getBenchmark(@PathVariable UUID orgId, @PathVariable UUID benchmarkId) {
        return benchmarkService.getBenchmark(orgId, benchmarkId);
    }

    @PutMapping(value = "/benchmarks/{benchmarkId}", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Update benchmark metadata")
    public BenchmarkDtos.BenchmarkResponse updateBenchmark(
            @PathVariable UUID orgId,
            @PathVariable UUID benchmarkId,
            @Valid @RequestBody BenchmarkDtos.UpdateBenchmarkRequest request) {
        return benchmarkService.updateBenchmark(orgId, benchmarkId, request);
    }

    @DeleteMapping("/benchmarks/{benchmarkId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Delete benchmark (draft-only; blocked if published versions exist)")
    public void deleteBenchmark(@PathVariable UUID orgId, @PathVariable UUID benchmarkId) {
        benchmarkService.deleteBenchmark(orgId, benchmarkId);
    }

    @PostMapping(
            value = "/benchmarks/{benchmarkId}/versions",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create draft benchmark version")
    public BenchmarkDtos.BenchmarkVersionResponse createVersion(
            @PathVariable UUID orgId,
            @PathVariable UUID benchmarkId,
            @Valid @RequestBody BenchmarkDtos.CreateVersionRequest request) {
        return benchmarkService.createVersion(orgId, benchmarkId, request);
    }

    @GetMapping(value = "/benchmarks/{benchmarkId}/versions", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "List versions for a benchmark")
    public List<BenchmarkDtos.BenchmarkVersionResponse> listVersions(
            @PathVariable UUID orgId,
            @PathVariable UUID benchmarkId) {
        return benchmarkService.listVersions(orgId, benchmarkId);
    }

    @GetMapping(value = "/benchmark-versions/{versionId}", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Get benchmark version")
    public BenchmarkDtos.BenchmarkVersionResponse getVersion(
            @PathVariable UUID orgId,
            @PathVariable UUID versionId) {
        return benchmarkService.getVersion(orgId, versionId);
    }

    @PostMapping(value = "/benchmark-versions/{versionId}/publish", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Publish version (freeze immutable evaluation snapshot)")
    public BenchmarkDtos.PublishVersionResponse publishVersion(
            @PathVariable UUID orgId,
            @PathVariable UUID versionId) {
        return benchmarkService.publishVersion(orgId, versionId);
    }

    @PostMapping(
            value = "/benchmark-versions/{versionId}/tasks",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Add task to a draft version")
    public BenchmarkDtos.TaskResponse createTask(
            @PathVariable UUID orgId,
            @PathVariable UUID versionId,
            @Valid @RequestBody BenchmarkDtos.CreateTaskRequest request) {
        return benchmarkService.createTask(orgId, versionId, request);
    }

    @GetMapping(value = "/benchmark-versions/{versionId}/tasks", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "List tasks in a version")
    public List<BenchmarkDtos.TaskResponse> listTasks(
            @PathVariable UUID orgId,
            @PathVariable UUID versionId) {
        return benchmarkService.listTasks(orgId, versionId);
    }

    @GetMapping(value = "/tasks/{taskId}", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Get task (hidden suite content restricted by RBAC)")
    public BenchmarkDtos.TaskResponse getTask(@PathVariable UUID orgId, @PathVariable UUID taskId) {
        return benchmarkService.getTask(orgId, taskId);
    }

    @PutMapping(
            value = "/tasks/{taskId}",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Update task (draft versions only)")
    public BenchmarkDtos.TaskResponse updateTask(
            @PathVariable UUID orgId,
            @PathVariable UUID taskId,
            @Valid @RequestBody BenchmarkDtos.UpdateTaskRequest request) {
        return benchmarkService.updateTask(orgId, taskId, request);
    }

    @DeleteMapping("/tasks/{taskId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Delete task (draft versions only)")
    public void deleteTask(@PathVariable UUID orgId, @PathVariable UUID taskId) {
        benchmarkService.deleteTask(orgId, taskId);
    }

    @PutMapping(
            value = "/tasks/{taskId}/suites/visible",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Upsert visible test suite")
    public BenchmarkDtos.SuiteResponse upsertVisibleSuite(
            @PathVariable UUID orgId,
            @PathVariable UUID taskId,
            @Valid @RequestBody BenchmarkDtos.UpsertSuiteRequest request) {
        return benchmarkService.upsertVisibleSuite(orgId, taskId, request);
    }

    @GetMapping(value = "/tasks/{taskId}/suites/visible", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Get visible test suite")
    public BenchmarkDtos.SuiteResponse getVisibleSuite(@PathVariable UUID orgId, @PathVariable UUID taskId) {
        return benchmarkService.getVisibleSuite(orgId, taskId);
    }

    @PutMapping(
            value = "/tasks/{taskId}/suites/hidden",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Upsert hidden test suite (DEVELOPER+/AUTHOR+)")
    public BenchmarkDtos.SuiteResponse upsertHiddenSuite(
            @PathVariable UUID orgId,
            @PathVariable UUID taskId,
            @Valid @RequestBody BenchmarkDtos.UpsertSuiteRequest request) {
        return benchmarkService.upsertHiddenSuite(orgId, taskId, request);
    }

    @GetMapping(value = "/tasks/{taskId}/suites/hidden", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Get hidden suite; fixture content omitted for VIEWER/EVALUATOR")
    public BenchmarkDtos.SuiteResponse getHiddenSuite(@PathVariable UUID orgId, @PathVariable UUID taskId) {
        return benchmarkService.getHiddenSuite(orgId, taskId);
    }

    @GetMapping(value = "/tasks/{taskId}/agent-context", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Agent-facing task context (never includes hidden suites or reference patches)")
    public BenchmarkDtos.AgentTaskContext getAgentTaskContext(
            @PathVariable UUID orgId,
            @PathVariable UUID taskId) {
        return benchmarkService.getAgentTaskContext(orgId, taskId);
    }
}
