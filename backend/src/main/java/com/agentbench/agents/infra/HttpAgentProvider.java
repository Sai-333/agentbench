package com.agentbench.agents.infra;

import com.agentbench.agents.domain.AdapterType;
import com.agentbench.agents.domain.spi.AgentInvocationRequest;
import com.agentbench.agents.domain.spi.AgentInvocationResult;
import com.agentbench.agents.domain.spi.AgentInvocationStatus;
import com.agentbench.agents.domain.spi.AgentProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/**
 * Generic HTTP webhook-style agent adapter. Posts invocation payload; expects JSON with {@code patch}.
 */
@Component
public class HttpAgentProvider implements AgentProvider {

    public static final String PROVIDER_VERSION = "http-v1";

    private static final Logger log = LoggerFactory.getLogger(HttpAgentProvider.class);

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final Duration defaultTimeout;

    @Autowired
    public HttpAgentProvider(
            ObjectMapper objectMapper,
            @Value("${agentbench.agents.http.default-timeout-sec:60}") int defaultTimeoutSec) {
        this.objectMapper = objectMapper;
        this.defaultTimeout = Duration.ofSeconds(Math.max(5, defaultTimeoutSec));
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    HttpAgentProvider(ObjectMapper objectMapper, HttpClient httpClient, Duration defaultTimeout) {
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
        this.defaultTimeout = defaultTimeout;
    }

    @Override
    public String adapterType() {
        return AdapterType.HTTP.name();
    }

    @Override
    public AgentInvocationResult invoke(AgentInvocationRequest request) {
        JsonNode config = readConfig(request.agent().configJson());
        String endpoint = config.path("endpoint").asText(null);
        if (endpoint == null || endpoint.isBlank()) {
            return AgentInvocationResult.failure(
                    AgentInvocationStatus.FAILED,
                    "AGENT_CONFIG_INVALID",
                    "HTTP agent requires config.endpoint",
                    null,
                    Map.of());
        }

        Duration timeout = request.timeout() != null ? request.timeout() : defaultTimeout;
        if (config.has("timeoutSec")) {
            timeout = Duration.ofSeconds(Math.max(5, config.path("timeoutSec").asInt(60)));
        }

        try {
            ObjectNode body = objectMapper.createObjectNode();
            body.put("correlationId", request.correlationId().toString());
            body.put("evaluationId", request.evaluationId().toString());
            body.put("taskId", request.taskId().toString());
            body.put("taskPrompt", request.taskPrompt() == null ? "" : request.taskPrompt());
            if (request.visibleContextJson() != null) {
                body.set("visibleContext", objectMapper.readTree(request.visibleContextJson()));
            }

            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint))
                    .timeout(timeout)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)));
            if (request.apiKey() != null && !request.apiKey().isBlank()) {
                builder.header("Authorization", "Bearer " + request.apiKey());
            }

            log.info("HTTP agent invoke evaluation={} endpointHost={}",
                    request.evaluationId(), URI.create(endpoint).getHost());

            HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                return AgentInvocationResult.failure(
                        AgentInvocationStatus.FAILED,
                        "AGENT_HTTP_" + response.statusCode(),
                        SecretRedactor.sanitizeError("HTTP agent returned " + response.statusCode()),
                        null,
                        Map.of("httpStatus", response.statusCode())
                );
            }
            JsonNode root = objectMapper.readTree(response.body());
            String patch = root.path("patch").asText(null);
            if (patch == null || patch.isBlank()) {
                return AgentInvocationResult.failure(
                        AgentInvocationStatus.FAILED,
                        "AGENT_EMPTY_PATCH",
                        "HTTP agent response missing patch field",
                        null,
                        Map.of());
            }
            return AgentInvocationResult.success(
                    patch,
                    config.path("model").asText(null),
                    PROVIDER_VERSION,
                    intOrNull(root.path("usage").path("promptTokens")),
                    intOrNull(root.path("usage").path("completionTokens")),
                    intOrNull(root.path("usage").path("totalTokens")),
                    null,
                    Map.of("endpointHost", URI.create(endpoint).getHost())
            );
        } catch (java.net.http.HttpTimeoutException ex) {
            return AgentInvocationResult.failure(
                    AgentInvocationStatus.TIMED_OUT,
                    "AGENT_TIMEOUT",
                    "HTTP agent request timed out",
                    null,
                    Map.of());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return AgentInvocationResult.failure(
                    AgentInvocationStatus.CANCELLED,
                    "AGENT_INTERRUPTED",
                    "HTTP agent request interrupted",
                    null,
                    Map.of());
        } catch (Exception ex) {
            return AgentInvocationResult.failure(
                    AgentInvocationStatus.FAILED,
                    "AGENT_ERROR",
                    SecretRedactor.sanitizeError(ex.getMessage()),
                    null,
                    Map.of());
        }
    }

    private JsonNode readConfig(String configJson) {
        try {
            return objectMapper.readTree(configJson == null || configJson.isBlank() ? "{}" : configJson);
        } catch (Exception ex) {
            return objectMapper.createObjectNode();
        }
    }

    private static Integer intOrNull(JsonNode node) {
        return node == null || node.isMissingNode() || node.isNull() ? null : node.asInt();
    }
}
