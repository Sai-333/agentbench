package com.agentbench.evaluation.api;

import com.agentbench.evaluation.api.dto.FlakeQuarantineDtos;
import com.agentbench.evaluation.service.FlakeQuarantineService;
import com.agentbench.platform.api.ApiPaths;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * OWNER/ADMIN manage known-flake quarantines; VIEWER may list.
 */
@RestController
@RequestMapping(ApiPaths.ORG_BASE + "/flake-quarantines")
@Tag(name = "Flake quarantine", description = "Known-flaky case/suite quarantine (R5 scoring fairness)")
@SecurityRequirement(name = "bearerAuth")
public class FlakeQuarantineController {

    private final FlakeQuarantineService flakeQuarantineService;

    public FlakeQuarantineController(FlakeQuarantineService flakeQuarantineService) {
        this.flakeQuarantineService = flakeQuarantineService;
    }

    @GetMapping
    @Operation(summary = "List flake quarantines (VIEWER+)")
    public List<FlakeQuarantineDtos.Response> list(
            @PathVariable UUID orgId,
            @RequestParam(defaultValue = "false") boolean includeInactive) {
        return flakeQuarantineService.list(orgId, includeInactive);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create flake quarantine (OWNER/ADMIN)")
    public FlakeQuarantineDtos.Response create(
            @PathVariable UUID orgId,
            @Valid @RequestBody FlakeQuarantineDtos.CreateRequest request) {
        return flakeQuarantineService.create(orgId, request);
    }

    @DeleteMapping("/{quarantineId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Deactivate flake quarantine (OWNER/ADMIN)")
    public void deactivate(
            @PathVariable UUID orgId,
            @PathVariable UUID quarantineId) {
        flakeQuarantineService.deactivate(orgId, quarantineId);
    }
}
