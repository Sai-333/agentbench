package com.agentbench.platform.gpu;

import com.agentbench.platform.api.ApiPaths;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Platform-scoped local GPU probe. Not under {@code /api/v1/orgs/{orgId}}.
 * The score comes from the API process that handles the request. A machine
 * without a GPU stores an unavailable row and no number.
 */
@RestController
@RequestMapping(ApiPaths.API_V1)
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "GPU probe", description = "Local GPU device, API, and transfer score from this API process")
public class GpuBenchmarkController {

    private final GpuBenchmarkService gpuBenchmarkService;

    public GpuBenchmarkController(GpuBenchmarkService gpuBenchmarkService) {
        this.gpuBenchmarkService = gpuBenchmarkService;
    }

    @PostMapping(value = "/gpu-benchmarks", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Run the local GPU probe and store device, API, and score")
    public GpuBenchmarkDtos.Result run() {
        return gpuBenchmarkService.run();
    }

    @GetMapping(value = "/gpu-benchmarks", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "List recent local GPU probe results")
    public List<GpuBenchmarkDtos.Result> list(
            @RequestParam(name = "limit", defaultValue = "20") int limit) {
        return gpuBenchmarkService.list(limit);
    }

    @GetMapping(value = "/gpu-benchmarks/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "One local GPU probe result")
    public GpuBenchmarkDtos.Result get(@PathVariable UUID id) {
        return gpuBenchmarkService.get(id);
    }
}
