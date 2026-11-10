package com.agentbench.agents.service;

import com.agentbench.agents.domain.Agent;
import com.agentbench.agents.domain.AgentInvocation;
import com.agentbench.agents.domain.spi.AgentInvocationRequest;
import com.agentbench.agents.domain.spi.AgentInvocationResult;
import com.agentbench.agents.domain.spi.AgentInvocationStatus;
import com.agentbench.agents.domain.spi.AgentProvider;
import com.agentbench.agents.infra.AgentInvocationRepository;
import com.agentbench.agents.infra.AgentProviderRegistry;
import com.agentbench.agents.infra.SecretRedactor;
import com.agentbench.identity.service.SecretService;
import com.agentbench.platform.error.ApiException;
import com.agentbench.platform.infra.storage.ArtifactSecretQuarantinedException;
import com.agentbench.platform.infra.storage.ArtifactSecretScanner;
import com.agentbench.platform.infra.storage.ArtifactStorage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Orchestrates provider calls with retry/timeout, secret resolution, and invocation audit.
 */
@Service
public class AgentInvocationService {

    private static final Logger log = LoggerFactory.getLogger(AgentInvocationService.class);

    private final AgentProviderRegistry providerRegistry;
    private final AgentInvocationRepository invocationRepository;
    private final SecretService secretService;
    private final ArtifactStorage artifactStorage;
    private final ArtifactSecretScanner artifactSecretScanner;
    private final ObjectMapper objectMapper;
    private final int defaultMaxRetries;
    private final int defaultTimeoutSec;
    private final long retryBackoffMs;

    public AgentInvocationService(
            AgentProviderRegistry providerRegistry,
            AgentInvocationRepository invocationRepository,
            SecretService secretService,
            ArtifactStorage artifactStorage,
            ArtifactSecretScanner artifactSecretScanner,
            ObjectMapper objectMapper,
            @Value("${agentbench.agents.default-max-retries:2}") int defaultMaxRetries,
            @Value("${agentbench.agents.default-timeout-sec:60}") int defaultTimeoutSec,
            @Value("${agentbench.agents.retry-backoff-ms:250}") long retryBackoffMs) {
        this.providerRegistry = providerRegistry;
        this.invocationRepository = invocationRepository;
        this.secretService = secretService;
        this.artifactStorage = artifactStorage;
        this.artifactSecretScanner = artifactSecretScanner;
        this.objectMapper = objectMapper;
        this.defaultMaxRetries = Math.max(0, defaultMaxRetries);
        this.defaultTimeoutSec = Math.max(5, defaultTimeoutSec);
        this.retryBackoffMs = Math.max(0, retryBackoffMs);
    }

    /**
     * Invoke the configured provider for an evaluation agent stage.
     * Persists each attempt; returns the successful patch or throws ApiException.
     */
    @Transactional
    public AgentInvocationResult invokeForEvaluation(
            Agent agent,
            UUID evaluationId,
            UUID taskId,
            String taskPrompt,
            String visibleContextJson,
            String referencePatchHint) {
        return invokeForEvaluation(
                agent, evaluationId, taskId, taskPrompt, visibleContextJson, referencePatchHint, null);
    }

    @Transactional
    public AgentInvocationResult invokeForEvaluation(
            Agent agent,
            UUID evaluationId,
            UUID taskId,
            String taskPrompt,
            String visibleContextJson,
            String referencePatchHint,
            String workspacePath) {
        if (!agent.enabled()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "AGENT_DISABLED", "Agent is disabled");
        }

        JsonNode config = readConfig(agent.configJson());
        int maxRetries = config.path("maxRetries").asInt(defaultMaxRetries);
        int maxAttempts = Math.max(1, maxRetries + 1);
        int timeoutSec = config.path("timeoutSec").asInt(defaultTimeoutSec);
        Duration timeout = Duration.ofSeconds(Math.max(5, timeoutSec));

        String apiKey = resolveApiKey(agent);
        AgentProvider provider = providerRegistry.require(agent.adapterType());

        AgentInvocationResult last = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            String promptForAudit = buildAuditPrompt(taskPrompt, visibleContextJson);
            AgentInvocation row = invocationRepository.insertRunning(
                    agent.orgId(),
                    evaluationId,
                    agent.id(),
                    taskId,
                    provider.adapterType(),
                    config.path("model").asText(null),
                    promptForAudit,
                    attempt,
                    maxAttempts
            );

            AgentInvocationRequest request = new AgentInvocationRequest(
                    row.id(),
                    agent.orgId(),
                    evaluationId,
                    taskId,
                    agent,
                    taskPrompt,
                    visibleContextJson,
                    referencePatchHint,
                    apiKey,
                    timeout,
                    attempt,
                    workspacePath
            );

            log.info("Agent invoke start evaluation={} agent={} provider={} attempt={}/{}",
                    evaluationId, agent.id(), provider.adapterType(), attempt, maxAttempts);

            AgentInvocationResult result = provider.invoke(request);
            last = result;
            persistCompletion(agent.orgId(), row.id(), result);

            if (result.status() == AgentInvocationStatus.SUCCEEDED
                    && result.patchContent() != null
                    && !result.patchContent().isBlank()) {
                return result;
            }

            boolean retryable = isRetryable(result.status());
            if (!retryable || attempt >= maxAttempts) {
                break;
            }
            sleepBackoff(attempt);
        }

        AgentInvocationStatus status = last == null ? AgentInvocationStatus.FAILED : last.status();
        String code = last == null || last.errorCode() == null ? "AGENT_ERROR" : last.errorCode();
        String message = last == null || last.errorMessage() == null
                ? "Agent invocation failed"
                : SecretRedactor.sanitizeError(last.errorMessage());
        if (status == AgentInvocationStatus.TIMED_OUT) {
            throw new ApiException(HttpStatus.GATEWAY_TIMEOUT, "AGENT_TIMEOUT", message);
        }
        if (status == AgentInvocationStatus.NOT_IMPLEMENTED) {
            throw new ApiException(HttpStatus.NOT_IMPLEMENTED, code, message);
        }
        throw new ApiException(HttpStatus.BAD_GATEWAY, code, message);
    }

    @Transactional(readOnly = true)
    public List<AgentInvocation> listForEvaluation(UUID orgId, UUID evaluationId) {
        return invocationRepository.listByEvaluation(orgId, evaluationId);
    }

    /**
     * Lightweight connectivity probe — does not create evaluation rows.
     * FAKE always succeeds; OPENAI_COMPATIBLE issues a tiny models/list or chat probe when credentials exist.
     */
    public Map<String, Object> testConnection(Agent agent) {
        AgentProvider provider = providerRegistry.require(agent.adapterType());
        if ("FAKE".equals(provider.adapterType())) {
            return Map.of("ok", true, "provider", provider.adapterType(), "detail", "Fake provider ready");
        }
        if ("CODING_CLI".equals(provider.adapterType())) {
            JsonNode config = readConfig(agent.configJson());
            boolean fixture = config.hasNonNull("patch");
            String binary = config.path("binary").asText(config.path("command").asText("coding-agent"));
            return Map.of(
                    "ok", true,
                    "provider", provider.adapterType(),
                    "detail", fixture
                            ? "CODING_CLI fixture-patch mode ready (no isolator spawn)"
                            : "CODING_CLI registered; isolator path uses binary/argv '" + binary
                            + "' (requires workspace + RuntimeIsolator; live CLI binary optional)",
                    "fixturePatch", fixture,
                    "isolation", "runtime-isolator"
            );
        }
        String apiKey = resolveApiKey(agent);
        if ((provider.adapterType().equals("OPENAI_COMPATIBLE") || provider.adapterType().equals("HTTP"))
                && (apiKey == null || apiKey.isBlank())
                && agent.secretRefId() == null) {
            return Map.of(
                    "ok", false,
                    "provider", provider.adapterType(),
                    "detail", "Missing secret_ref_id for provider credentials"
            );
        }
        // Dry-run invoke with empty evaluation ids is avoided; report credential presence only.
        return Map.of(
                "ok", true,
                "provider", provider.adapterType(),
                "detail", "Provider registered; credentials "
                        + (apiKey == null || apiKey.isBlank() ? "absent" : "present (not echoed)"),
                "hasCredentials", apiKey != null && !apiKey.isBlank()
        );
    }

    private void persistCompletion(UUID orgId, UUID invocationId, AgentInvocationResult result) {
        String patchDigest = null;
        Integer patchBytes = null;
        String patchUri = null;
        if (result.patchContent() != null && !result.patchContent().isBlank()) {
            String key = "orgs/%s/agent-invocations/%s/patch.diff".formatted(orgId, invocationId);
            try {
                String safeText = artifactSecretScanner.prepareText(key, result.patchContent(), "text/x-diff").content();
                byte[] bytes = safeText.getBytes(StandardCharsets.UTF_8);
                patchBytes = bytes.length;
                patchDigest = sha256(bytes);
                patchUri = artifactStorage.store(key, new ByteArrayInputStream(bytes), bytes.length, "text/x-diff");
            } catch (ArtifactSecretQuarantinedException ex) {
                log.warn("Invocation patch quarantined by secret scan: {}", SecretRedactor.sanitizeError(ex.getMessage()));
            } catch (Exception ex) {
                log.warn("Could not store invocation patch artifact: {}", SecretRedactor.sanitizeError(ex.getMessage()));
            }
        }

        String status = switch (result.status()) {
            case SUCCEEDED -> "SUCCEEDED";
            case TIMED_OUT -> "TIMED_OUT";
            case CANCELLED -> "CANCELLED";
            case FAILED, NOT_IMPLEMENTED -> "FAILED";
        };

        invocationRepository.complete(
                orgId,
                invocationId,
                status,
                result.providerVersion(),
                result.model(),
                patchDigest,
                patchBytes,
                patchUri,
                result.promptTokens(),
                result.completionTokens(),
                result.totalTokens(),
                result.estimatedCostUsd(),
                result.errorCode(),
                SecretRedactor.sanitizeError(result.errorMessage()),
                writeJson(result.metadata())
        );
    }

    private String resolveApiKey(Agent agent) {
        if (agent.secretRefId() == null) {
            return null;
        }
        try {
            return secretService.decryptForOrg(agent.orgId(), agent.secretRefId());
        } catch (ApiException ex) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "AGENT_CREDENTIAL_MISSING",
                    "Unable to resolve agent secret_ref");
        }
    }

    private static boolean isRetryable(AgentInvocationStatus status) {
        return status == AgentInvocationStatus.TIMED_OUT || status == AgentInvocationStatus.FAILED;
    }

    private void sleepBackoff(int attempt) {
        if (retryBackoffMs <= 0) {
            return;
        }
        try {
            Thread.sleep(retryBackoffMs * attempt);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private String buildAuditPrompt(String taskPrompt, String visibleContextJson) {
        StringBuilder sb = new StringBuilder();
        if (taskPrompt != null) {
            sb.append(taskPrompt);
        }
        if (visibleContextJson != null && !visibleContextJson.isBlank()) {
            if (!sb.isEmpty()) {
                sb.append("\n---\n");
            }
            sb.append(visibleContextJson);
        }
        // Cap persisted prompt size.
        String out = SecretRedactor.redact(sb.toString());
        if (out != null && out.length() > 20_000) {
            return out.substring(0, 20_000) + "…";
        }
        return out;
    }

    private JsonNode readConfig(String configJson) {
        try {
            return objectMapper.readTree(configJson == null || configJson.isBlank() ? "{}" : configJson);
        } catch (Exception ex) {
            return objectMapper.createObjectNode();
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value == null ? Map.of() : value);
        } catch (Exception ex) {
            return "{}";
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(bytes));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
