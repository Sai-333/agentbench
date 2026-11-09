package com.agentbench.agents.service;

import com.agentbench.agents.api.dto.AgentDtos;
import com.agentbench.agents.domain.AdapterType;
import com.agentbench.agents.domain.Agent;
import com.agentbench.agents.infra.AgentProviderRegistry;
import com.agentbench.agents.infra.AgentRepository;
import com.agentbench.agents.infra.SecretRedactor;
import com.agentbench.identity.domain.OrgRole;
import com.agentbench.identity.infra.SecretRefRepository;
import com.agentbench.identity.security.OrgAccessService;
import com.agentbench.identity.security.UserPrincipal;
import com.agentbench.platform.error.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class AgentService {

    private final AgentRepository agentRepository;
    private final OrgAccessService orgAccessService;
    private final SecretRefRepository secretRefRepository;
    private final AgentInvocationService invocationService;
    private final AgentProviderRegistry providerRegistry;
    private final ObjectMapper objectMapper;

    public AgentService(
            AgentRepository agentRepository,
            OrgAccessService orgAccessService,
            SecretRefRepository secretRefRepository,
            AgentInvocationService invocationService,
            AgentProviderRegistry providerRegistry,
            ObjectMapper objectMapper) {
        this.agentRepository = agentRepository;
        this.orgAccessService = orgAccessService;
        this.secretRefRepository = secretRefRepository;
        this.invocationService = invocationService;
        this.providerRegistry = providerRegistry;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public AgentDtos.AgentResponse create(UUID orgId, AgentDtos.CreateAgentRequest request) {
        orgAccessService.requireMembership(orgId, OrgRole.DEVELOPER);
        UserPrincipal principal = orgAccessService.requirePrincipal();
        String adapterType = normalizeAdapterType(request.adapterType());
        validateSecretRef(orgId, request.secretRefId(), adapterType);
        rejectInlineSecrets(request.config());
        Map<String, Object> sanitizedConfig = sanitizeConfig(request.config());
        try {
            Agent agent = agentRepository.insert(
                    orgId,
                    request.name().trim(),
                    adapterType,
                    request.secretRefId(),
                    writeJson(sanitizedConfig),
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
        return toResponse(require(orgId, agentId));
    }

    @Transactional
    public AgentDtos.AgentResponse update(UUID orgId, UUID agentId, AgentDtos.UpdateAgentRequest request) {
        orgAccessService.requireMembership(orgId, OrgRole.DEVELOPER);
        Agent existing = require(orgId, agentId);

        String name = request.name() == null ? existing.name() : request.name().trim();
        if (name.isBlank()) {
            throw ApiException.validation("name must not be blank");
        }
        String adapterType = request.adapterType() == null
                ? existing.adapterType()
                : normalizeAdapterType(request.adapterType());

        UUID secretRefId = existing.secretRefId();
        if (Boolean.TRUE.equals(request.clearSecretRef())) {
            secretRefId = null;
        } else if (request.secretRefId() != null) {
            secretRefId = request.secretRefId();
        }
        validateSecretRef(orgId, secretRefId, adapterType);

        if (request.config() != null) {
            rejectInlineSecrets(request.config());
        }
        Map<String, Object> config = request.config() == null
                ? readMap(existing.configJson())
                : sanitizeConfig(request.config());

        boolean enabled = request.enabled() == null ? existing.enabled() : request.enabled();

        try {
            Agent updated = agentRepository.update(
                    orgId, agentId, name, adapterType, secretRefId, writeJson(config), enabled);
            return toResponse(updated);
        } catch (DuplicateKeyException ex) {
            throw ApiException.conflict("Agent name already exists in organization");
        }
    }

    @Transactional
    public void delete(UUID orgId, UUID agentId) {
        orgAccessService.requireMembership(orgId, OrgRole.ADMIN);
        Agent existing = require(orgId, agentId);
        try {
            agentRepository.delete(orgId, agentId);
        } catch (DataIntegrityViolationException ex) {
            // Referenced by evaluations — soft-disable instead of hard delete.
            agentRepository.update(
                    orgId,
                    agentId,
                    existing.name(),
                    existing.adapterType(),
                    existing.secretRefId(),
                    existing.configJson(),
                    false
            );
            throw ApiException.conflict("Agent is referenced by evaluations; disabled instead of deleted");
        }
    }

    @Transactional(readOnly = true)
    public AgentDtos.TestConnectionResponse testConnection(UUID orgId, UUID agentId) {
        orgAccessService.requireMembership(orgId, OrgRole.DEVELOPER);
        Agent agent = require(orgId, agentId);
        Map<String, Object> result = invocationService.testConnection(agent);
        return new AgentDtos.TestConnectionResponse(
                Boolean.TRUE.equals(result.get("ok")),
                String.valueOf(result.get("provider")),
                String.valueOf(result.get("detail")),
                result.get("hasCredentials") instanceof Boolean b ? b : null
        );
    }

    private Agent require(UUID orgId, UUID agentId) {
        return agentRepository.find(orgId, agentId)
                .orElseThrow(() -> ApiException.notFound("Agent not found"));
    }

    private String normalizeAdapterType(String raw) {
        AdapterType type;
        try {
            type = AdapterType.fromString(raw).normalize();
        } catch (IllegalArgumentException ex) {
            throw ApiException.validation(ex.getMessage());
        }
        // Persist normalized type; SPRING_AI becomes OPENAI_COMPATIBLE.
        // Keep SPRING_AI only if caller explicitly wants legacy label — we normalize.
        if (!providerRegistry.supports(type.name())) {
            throw ApiException.validation("No provider registered for " + type.name());
        }
        // Allow persisting SPRING_AI for backward compat when requested raw.
        if ("SPRING_AI".equalsIgnoreCase(raw.trim())) {
            return AdapterType.SPRING_AI.name();
        }
        return type.name();
    }

    private void validateSecretRef(UUID orgId, UUID secretRefId, String adapterType) {
        if (secretRefId == null) {
            return;
        }
        secretRefRepository.findById(orgId, secretRefId)
                .orElseThrow(() -> ApiException.validation("secretRefId not found in organization"));
    }

    private void rejectInlineSecrets(Map<String, Object> config) {
        if (config == null) {
            return;
        }
        for (String key : config.keySet()) {
            if (SecretRedactor.looksLikeSecretKey(key)) {
                throw ApiException.validation(
                        "Do not put secrets in config; store API keys via org secret_refs and secretRefId");
            }
        }
    }

    /**
     * Strip any accidental secret-like keys from config maps before persistence/response.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> sanitizeConfig(Map<String, Object> config) {
        if (config == null) {
            return Map.of();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : config.entrySet()) {
            if (SecretRedactor.looksLikeSecretKey(entry.getKey())) {
                continue;
            }
            out.put(entry.getKey(), entry.getValue());
        }
        return out;
    }

    private AgentDtos.AgentResponse toResponse(Agent agent) {
        return new AgentDtos.AgentResponse(
                agent.id(),
                agent.orgId(),
                agent.name(),
                agent.adapterType(),
                agent.secretRefId(),
                sanitizeConfig(readMap(agent.configJson())),
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
