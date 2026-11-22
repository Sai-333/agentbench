package com.agentbench.platform.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Non-tenant platform metadata. Product APIs will live under {@link ApiPaths#ORG_BASE}.
 */
@RestController
@RequestMapping(ApiPaths.API_V1)
@Tag(name = "Platform", description = "Platform metadata (Milestone 1)")
public class PlatformInfoController {

    @GetMapping(value = "/info", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Platform info", description = "Returns build/runtime metadata for the modular monolith.")
    public Map<String, Object> info() {
        return Map.of(
                "name", "agentbench",
                "milestone", "M9",
                "apiBase", ApiPaths.API_V1,
                "orgScopedApiBase", "/api/v1/orgs/{orgId}/...",
                "auth", "JWT bearer (local email/password + optional Google/GitHub OIDC)",
                "analytics", "org-private overview/leaderboard/comparisons/costs/exports (json/csv/pdf); opt-in public leaderboards + signed embeds",
                "regressionRuns", "AgentBench application regression: platform-scoped POST/GET /api/v1/regression-runs (OWNER/ADMIN of any org; git SHA/branch; not consumer/org tenant data)",
                "gpuBenchmark", "Local GPU probe: POST/GET /api/v1/gpu-benchmarks records device, API, and a transfer score from this API process. Unavailable rows have no score when no GPU is present or the probe cannot run. CI does not require a GPU.",
                "gpuGraphics", "Local graphics frame-time probe: POST/GET /api/v1/gpu-graphics records device, frame count, and average frame time from this API process. Unavailable rows have no score when no GPU is present or the probe cannot run. CI does not require a GPU.",
                "readiness", "production-minded Compose/K8s path; Docker isolation not hostile multi-tenant"
        );
    }
}
