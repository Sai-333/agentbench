package com.agentbench.agents.api;

import com.agentbench.agents.api.dto.AgentDtos;
import com.agentbench.agents.service.AgentService;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping(ApiPaths.ORG_BASE + "/agents")
@Tag(name = "Agents", description = "Org-scoped agent registry (M4 stub adapters)")
@SecurityRequirement(name = "bearerAuth")
public class AgentController {

    private final AgentService agentService;

    public AgentController(AgentService agentService) {
        this.agentService = agentService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Register an agent")
    public AgentDtos.AgentResponse create(
            @PathVariable UUID orgId,
            @Valid @RequestBody AgentDtos.CreateAgentRequest request) {
        return agentService.create(orgId, request);
    }

    @GetMapping
    @Operation(summary = "List agents")
    public List<AgentDtos.AgentResponse> list(@PathVariable UUID orgId) {
        return agentService.list(orgId);
    }

    @GetMapping("/{agentId}")
    @Operation(summary = "Get agent")
    public AgentDtos.AgentResponse get(@PathVariable UUID orgId, @PathVariable UUID agentId) {
        return agentService.get(orgId, agentId);
    }
}
