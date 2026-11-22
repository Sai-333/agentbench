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
 * Platform-scoped local graphics probe. Not under {@code /api/v1/orgs/{orgId}}.
 * The score is the average frame time from the API process that handles the
 * request. A machine without a GPU stores an unavailable row and no number.
 */
@RestController
@RequestMapping(ApiPaths.API_V1)
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "GPU graphics", description = "Local GPU device, frame count, and frame time from this API process")
public class GpuGraphicsController {

    private final GpuGraphicsService gpuGraphicsService;

    public GpuGraphicsController(GpuGraphicsService gpuGraphicsService) {
        this.gpuGraphicsService = gpuGraphicsService;
    }

    @PostMapping(value = "/gpu-graphics", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Run the local graphics probe and store device, frames, and frame time")
    public GpuGraphicsDtos.Result run() {
        return gpuGraphicsService.run();
    }

    @GetMapping(value = "/gpu-graphics", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "List recent local graphics frame-time results")
    public List<GpuGraphicsDtos.Result> list(
            @RequestParam(name = "limit", defaultValue = "20") int limit) {
        return gpuGraphicsService.list(limit);
    }

    @GetMapping(value = "/gpu-graphics/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "One local graphics frame-time result")
    public GpuGraphicsDtos.Result get(@PathVariable UUID id) {
        return gpuGraphicsService.get(id);
    }
}
