package com.agentbench.agents.service;

import com.agentbench.agents.api.dto.AgentDtos;
import com.agentbench.agents.domain.Agent;
import com.agentbench.agents.infra.AgentRepository;
import com.agentbench.identity.domain.OrgRole;
import com.agentbench.identity.security.OrgAccessService;
import com.agentbench.identity.security.UserPrincipal;
import com.agentbench.platform.error.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
public class AgentService {

    private final AgentRepository agentRepository;
    private final OrgAccessService orgAccessService;
    private final ObjectMapper objectMapper;

    public AgentService(AgentRepository agentRepository, OrgAccessService orgAccessService, ObjectMapper objectMapper) {
        this.agentRepository = agentRepository;
        this.orgAccessService = orgAccessService;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public AgentDtos.AgentResponse create(UUID orgId, AgentDtos.CreateAgentRequest request) {
        orgAccessService.requireMembership(orgId, OrgRole.DEVELOPER);
        UserPrincipal principal = orgAccessService.requirePrincipal();
        String adapterType = request.adapterType().trim().toUpperCase(Locale.ROOT);
        if (!List.of("FAKE", "HTTP", "SPRING_AI").contains(adapterType)) {
            throw ApiException.validation("Unsupported adapterType: " + request.adapterType());
        }
        try {
            Agent agent = agentRepository.insert(
                    orgId,
                    request.name().trim(),
                    adapterType,
                    request.secretRefId(),
                    writeJson(request.config() == null ? Map.of() : request.config()),
                    principal.userId()
            );
            return toResponse(agent);
        } catch (DuplicateKeyException ex) {
            throw ApiException.conflict("Agent name already exists in organization");
        }
    }

    @Transactional(readOnly = true)
    public List<AgentDtos.AgentResponse> list(UUID orgId) {
        orgAccessService.requireMembership(orgId, OrgRole.VIEWER);
        return agentRepository.list(orgId).stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public AgentDtos.AgentResponse get(UUID orgId, UUID agentId) {
        orgAccessService.requireMembership(orgId, OrgRole.VIEWER);
        return toResponse(agentRepository.find(orgId, agentId)
                .orElseThrow(() -> ApiException.notFound("Agent not found")));
    }

    private AgentDtos.AgentResponse toResponse(Agent agent) {
        return new AgentDtos.AgentResponse(
                agent.id(),
                agent.orgId(),
                agent.name(),
                agent.adapterType(),
                agent.secretRefId(),
                readMap(agent.configJson()),
                agent.enabled(),
                agent.createdBy(),
                agent.createdAt(),
                agent.updatedAt()
        );
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readMap(String json) {
        try {
            if (json == null || json.isBlank()) {
                return Map.of();
            }
            return objectMapper.readValue(json, Map.class);
        } catch (Exception ex) {
            return Map.of();
        }
    }
}
