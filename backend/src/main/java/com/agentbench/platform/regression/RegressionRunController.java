package com.agentbench.platform.regression;

import com.agentbench.platform.api.ApiPaths;
import com.agentbench.platform.error.ApiException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

/**
 * Platform-scoped regression ingest and reads. Not under {@code /api/v1/orgs/{orgId}}.
 * OWNER or ADMIN of any organization may call these routes. See docs/REGRESSION_RUNS.md.
 */
@RestController
@RequestMapping(ApiPaths.API_V1)
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "Regression runs", description = "Platform-scoped CI regression ingest (JUnit XML, pytest JSON, or canonical cases)")
public class RegressionRunController {

    private final RegressionRunService regressionRunService;

    public RegressionRunController(RegressionRunService regressionRunService) {
        this.regressionRunService = regressionRunService;
    }

    @PostMapping(value = "/regression-runs", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Ingest a JUnit XML, pytest JSON, or canonical cases report")
    public RegressionDtos.RunDetail ingest(HttpServletRequest request) throws IOException {
        byte[] bytes = request.getInputStream().readNBytes(RegressionRunService.MAX_BODY_CHARS + 1);
        if (bytes.length > RegressionRunService.MAX_BODY_CHARS) {
            throw ApiException.validation("Report body exceeds 2 MB");
        }
        String body = new String(bytes, StandardCharsets.UTF_8);
        return regressionRunService.ingest(new RegressionRunService.IngestCommand(
                request.getContentType(),
                body,
                request.getParameter("gitRepo"),
                request.getParameter("gitBranch"),
                request.getParameter("gitSha"),
                request.getParameter("suiteName"),
                request.getParameter("startedAt"),
                request.getParameter("finishedAt")));
    }

    @GetMapping(value = "/regression-runs", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "List recent platform regression runs")
    public List<RegressionDtos.RunSummary> list(
            @RequestParam(name = "limit", defaultValue = "50") int limit) {
        return regressionRunService.list(limit);
    }

    @GetMapping(value = "/regression-runs/{runId}", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Regression run with per-case pass, fail, and skip rows")
    public RegressionDtos.RunDetail get(@PathVariable UUID runId) {
        return regressionRunService.get(runId);
    }
}
