package com.agentbench.agents.infra;

import com.agentbench.agents.domain.AdapterType;
import com.agentbench.agents.domain.spi.AgentInvocationRequest;
import com.agentbench.agents.domain.spi.AgentInvocationResult;
import com.agentbench.agents.domain.spi.AgentInvocationStatus;
import com.agentbench.agents.domain.spi.AgentProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * First real provider: OpenAI-compatible Chat Completions HTTP API.
 * No Spring AI types — plain HTTP client only.
 */
@Component
public class OpenAiCompatibleAgentProvider implements AgentProvider {

    public static final String PROVIDER_VERSION = "openai-compat-v1";

    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatibleAgentProvider.class);
    private static final Pattern DIFF_FENCE = Pattern.compile(
            "```(?:diff|patch)?\\s*([\\s\\S]*?)```", Pattern.CASE_INSENSITIVE);

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final String defaultBaseUrl;
    private final Duration defaultTimeout;

    @Autowired
    public OpenAiCompatibleAgentProvider(
            ObjectMapper objectMapper,
            @Value("${agentbench.agents.openai.default-base-url:https://api.openai.com}") String defaultBaseUrl,
            @Value("${agentbench.agents.openai.default-timeout-sec:60}") int defaultTimeoutSec) {
        this.objectMapper = objectMapper;
        this.defaultBaseUrl = trimSlash(defaultBaseUrl);
        this.defaultTimeout = Duration.ofSeconds(Math.max(5, defaultTimeoutSec));
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    /** Test/constructor hook. */
    OpenAiCompatibleAgentProvider(ObjectMapper objectMapper, HttpClient httpClient, String defaultBaseUrl, Duration defaultTimeout) {
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
        this.defaultBaseUrl = trimSlash(defaultBaseUrl);
        this.defaultTimeout = defaultTimeout;
    }

    @Override
    public String adapterType() {
        return AdapterType.OPENAI_COMPATIBLE.name();
    }

    @Override
    public AgentInvocationResult invoke(AgentInvocationRequest request) {
        return complete(request, new ChatCallDefaults(
                "OpenAI-compatible",
                PROVIDER_VERSION,
                defaultBaseUrl,
                "gpt-4o-mini",
                "OpenAI-compatible agent requires an org secret_ref API key"));
    }

    /**
     * Shared OpenAI-compatible Chat Completions call. Grok uses the same HTTP shape
     * with xAI defaults. Never log {@code request.apiKey()}.
     */
    AgentInvocationResult complete(AgentInvocationRequest request, ChatCallDefaults defaults) {
        if (request.apiKey() == null || request.apiKey().isBlank()) {
            return AgentInvocationResult.failure(
                    AgentInvocationStatus.FAILED,
                    "AGENT_CREDENTIAL_MISSING",
                    defaults.missingCredentialMessage(),
                    null,
                    Map.of());
        }

        JsonNode config = readConfig(request.agent().configJson());
        String baseUrl = trimSlash(config.path("baseUrl").asText(
                defaults.defaultBaseUrl() == null ? "" : defaults.defaultBaseUrl()));
        String configuredModel = config.path("model").asText("");
        String model = configuredModel.isBlank() ? defaults.defaultModel() : configuredModel;
        double temperature = config.path("temperature").asDouble(0.2);
        int maxTokens = config.path("maxTokens").asInt(4096);
        Duration timeout = request.timeout() != null ? request.timeout() : defaultTimeout;
        if (config.has("timeoutSec")) {
            timeout = Duration.ofSeconds(Math.max(5, config.path("timeoutSec").asInt(60)));
        }

        String systemPrompt = config.path("systemPrompt").asText("""
                You are a coding agent evaluated by AgentBench.
                Return ONLY a unified diff patch that fixes the task.
                Do not claim test results. Do not include explanations outside a diff/patch fence.
                """);
        String userPrompt = buildUserPrompt(request);

        try {
            ObjectNode body = objectMapper.createObjectNode();
            body.put("model", model);
            body.put("temperature", temperature);
            body.put("max_tokens", maxTokens);
            ArrayNode messages = body.putArray("messages");
            messages.addObject().put("role", "system").put("content", systemPrompt);
            messages.addObject().put("role", "user").put("content", userPrompt);

            String url = baseUrl.endsWith("/v1")
                    ? baseUrl + "/chat/completions"
                    : baseUrl + "/v1/chat/completions";

            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(timeout)
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + request.apiKey())
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                    .build();

            log.info("{} invoke evaluation={} agent={} model={} baseHost={}",
                    defaults.logLabel(),
                    request.evaluationId(),
                    request.agent().id(),
                    model,
                    URI.create(baseUrl).getHost());

            HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                return AgentInvocationResult.failure(
                        AgentInvocationStatus.FAILED,
                        "AGENT_HTTP_" + response.statusCode(),
                        SecretRedactor.sanitizeError("Provider HTTP " + response.statusCode() + ": " + truncate(response.body(), 300)),
                        model,
                        Map.of("httpStatus", response.statusCode())
                );
            }

            JsonNode root = objectMapper.readTree(response.body());
            String content = root.path("choices").path(0).path("message").path("content").asText("");
            String patch = extractPatch(content);
            if (patch == null || patch.isBlank()) {
                return AgentInvocationResult.failure(
                        AgentInvocationStatus.FAILED,
                        "AGENT_EMPTY_PATCH",
                        "Provider response did not contain a unified diff patch",
                        model,
                        Map.of("finishReason", root.path("choices").path(0).path("finish_reason").asText(null))
                );
            }

            Integer promptTokens = intOrNull(root.path("usage").path("prompt_tokens"));
            Integer completionTokens = intOrNull(root.path("usage").path("completion_tokens"));
            Integer totalTokens = intOrNull(root.path("usage").path("total_tokens"));
            BigDecimal cost = estimateCost(config, promptTokens, completionTokens);

            Map<String, Object> meta = new HashMap<>();
            meta.put("finishReason", root.path("choices").path(0).path("finish_reason").asText(null));
            meta.put("baseHost", URI.create(baseUrl).getHost());
            meta.put("responseId", root.path("id").asText(null));

            return AgentInvocationResult.success(
                    patch,
                    model,
                    defaults.providerVersion(),
                    promptTokens,
                    completionTokens,
                    totalTokens,
                    cost,
                    meta
            );
        } catch (java.net.http.HttpTimeoutException ex) {
            return AgentInvocationResult.failure(
                    AgentInvocationStatus.TIMED_OUT,
                    "AGENT_TIMEOUT",
                    defaults.logLabel() + " request timed out",
                    model,
                    Map.of());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return AgentInvocationResult.failure(
                    AgentInvocationStatus.CANCELLED,
                    "AGENT_INTERRUPTED",
                    defaults.logLabel() + " request interrupted",
                    model,
                    Map.of());
        } catch (Exception ex) {
            log.warn("{} invoke failed evaluation={}: {}",
                    defaults.logLabel(),
                    request.evaluationId(),
                    SecretRedactor.sanitizeError(ex.getMessage()));
            return AgentInvocationResult.failure(
                    AgentInvocationStatus.FAILED,
                    "AGENT_ERROR",
                    SecretRedactor.sanitizeError(ex.getMessage()),
                    model,
                    Map.of());
        }
    }

    static String extractPatch(String content) {
        if (content == null) {
            return null;
        }
        Matcher fence = DIFF_FENCE.matcher(content);
        if (fence.find()) {
            String inner = fence.group(1).trim();
            if (inner.startsWith("diff ") || inner.startsWith("--- ")) {
                return inner;
            }
            if (inner.contains("diff --git") || inner.contains("\n+++ ")) {
                return inner;
            }
        }
        String trimmed = content.trim();
        if (trimmed.startsWith("diff ") || trimmed.startsWith("--- ")) {
            return trimmed;
        }
        int idx = trimmed.indexOf("diff --git");
        if (idx >= 0) {
            return trimmed.substring(idx).trim();
        }
        return null;
    }

    private String buildUserPrompt(AgentInvocationRequest request) {
        StringBuilder sb = new StringBuilder();
        sb.append("Task prompt:\n").append(nullToEmpty(request.taskPrompt())).append("\n\n");
        if (request.visibleContextJson() != null && !request.visibleContextJson().isBlank()) {
            sb.append("Visible context (JSON, no hidden tests):\n")
                    .append(request.visibleContextJson())
                    .append("\n\n");
        }
        sb.append("Return a unified diff patch only.");
        return sb.toString();
    }

    private JsonNode readConfig(String configJson) {
        try {
            return objectMapper.readTree(configJson == null || configJson.isBlank() ? "{}" : configJson);
        } catch (Exception ex) {
            return objectMapper.createObjectNode();
        }
    }

    private static BigDecimal estimateCost(JsonNode config, Integer promptTokens, Integer completionTokens) {
        if (promptTokens == null && completionTokens == null) {
            return null;
        }
        double promptRate = config.path("costPer1kPromptTokens").asDouble(0);
        double completionRate = config.path("costPer1kCompletionTokens").asDouble(0);
        if (promptRate <= 0 && completionRate <= 0) {
            return null;
        }
        double cost = 0;
        if (promptTokens != null) {
            cost += (promptTokens / 1000.0) * promptRate;
        }
        if (completionTokens != null) {
            cost += (completionTokens / 1000.0) * completionRate;
        }
        return BigDecimal.valueOf(cost).setScale(8, RoundingMode.HALF_UP);
    }

    private static Integer intOrNull(JsonNode node) {
        return node == null || node.isMissingNode() || node.isNull() ? null : node.asInt();
    }

    private static String trimSlash(String url) {
        if (url == null) {
            return "";
        }
        String t = url.trim();
        while (t.endsWith("/")) {
            t = t.substring(0, t.length() - 1);
        }
        return t;
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
