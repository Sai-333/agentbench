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
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * Default model provider.
 * {@code XAI_API_KEY} (or an org secret ref) calls OpenAI-compatible chat at {@code https://api.x.ai/v1}.
 * When that key is empty and {@code CURSOR_API_KEY} is set, the call goes to
 * {@code POST https://api.cursor.com/v1/agents} with {@code model.id} set to the Grok model.
 * A legacy Cursor user API key is never sent to xAI and is never logged.
 */
@Component
public class GrokAgentProvider implements AgentProvider {

    public static final String PROVIDER_VERSION = "grok-v1";
    public static final String CURSOR_PROVIDER_VERSION = "grok-cursor-v1";

    /** Stable name of the org agent used when an evaluation omits {@code agentId}. */
    public static final String DEFAULT_AGENT_NAME = "Grok";

    /** xAI API base currently documented for OpenAI-compatible chat completions. */
    public static final String DEFAULT_BASE_URL = "https://api.x.ai/v1";

    /**
     * Cloud Agents create endpoint. Accepts a legacy Cursor user API key (HTTP Basic, empty password)
     * and Grok model ids such as {@code grok-4.7}.
     */
    public static final String CURSOR_AGENTS_URL = "https://api.cursor.com/v1/agents";

    /**
     * Flagship Grok model served by the xAI API and included for legacy Cursor API keys.
     * Override with {@code GROK_MODEL}.
     */
    public static final String DEFAULT_MODEL = "grok-4.7";

    /**
     * Grok model ids a legacy Cursor plan includes. {@link #DEFAULT_MODEL} is first.
     * {@code grok-4.6} and {@code grok-4.5} are selected with {@code GROK_MODEL}.
     */
    public static final List<String> LEGACY_CURSOR_GROK_MODELS = List.of(
            "grok-4.7",
            "grok-4.6",
            "grok-4.5");

    public static final String MISSING_KEY_MESSAGE =
            "Grok agent requires an API key. Set XAI_API_KEY or CURSOR_API_KEY, or store one in an org secret_ref";

    private static final Logger log = LoggerFactory.getLogger(GrokAgentProvider.class);
    private static final Duration POLL_INTERVAL = Duration.ofMillis(200);

    private final OpenAiCompatibleAgentProvider chat;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final String baseUrl;
    private final String model;
    private final String xaiApiKey;
    private final String cursorApiKey;
    private final String cursorAgentsUrl;

    @Autowired
    public GrokAgentProvider(
            OpenAiCompatibleAgentProvider chat,
            ObjectMapper objectMapper,
            @Value("${agentbench.agents.grok.base-url:" + DEFAULT_BASE_URL + "}") String baseUrl,
            @Value("${agentbench.agents.grok.model:" + DEFAULT_MODEL + "}") String model,
            @Value("${agentbench.agents.grok.api-key:}") String xaiApiKey,
            @Value("${agentbench.agents.grok.cursor-api-key:}") String cursorApiKey,
            @Value("${agentbench.agents.grok.cursor-agents-url:" + CURSOR_AGENTS_URL + "}") String cursorAgentsUrl) {
        this(
                chat,
                objectMapper,
                HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(10))
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build(),
                baseUrl,
                model,
                xaiApiKey,
                cursorApiKey,
                cursorAgentsUrl);
    }

    /** Test hook. Does not retain a real API key. */
    GrokAgentProvider(
            OpenAiCompatibleAgentProvider chat,
            ObjectMapper objectMapper,
            HttpClient httpClient,
            String baseUrl,
            String model,
            String xaiApiKey,
            String cursorApiKey,
            String cursorAgentsUrl) {
        this.chat = chat;
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
        this.baseUrl = blankTo(baseUrl, DEFAULT_BASE_URL);
        this.model = blankTo(model, DEFAULT_MODEL);
        this.xaiApiKey = xaiApiKey == null ? "" : xaiApiKey;
        this.cursorApiKey = cursorApiKey == null ? "" : cursorApiKey;
        this.cursorAgentsUrl = blankTo(cursorAgentsUrl, CURSOR_AGENTS_URL);
    }

    @Override
    public String adapterType() {
        return AdapterType.GROK.name();
    }

    public String defaultModel() {
        return model;
    }

    public String defaultBaseUrl() {
        return baseUrl;
    }

    public String cursorAgentsUrl() {
        return cursorAgentsUrl;
    }

    /**
     * Env key when no org secret ref is set.
     * {@code XAI_API_KEY} wins and stays on {@code https://api.x.ai/v1}.
     * {@code CURSOR_API_KEY} is used only when the xAI key is unset, and is sent to the Cursor agents API.
     * Blank values are ignored. This method never logs either value.
     */
    public static String selectEnvKey(String xaiApiKey, String cursorApiKey) {
        if (xaiApiKey != null && !xaiApiKey.isBlank()) {
            return xaiApiKey.trim();
        }
        if (cursorApiKey != null && !cursorApiKey.isBlank()) {
            return cursorApiKey.trim();
        }
        return null;
    }

    @Override
    public AgentInvocationResult invoke(AgentInvocationRequest request) {
        if (useCursor(request)) {
            return invokeCursor(request);
        }
        return chat.complete(request, new ChatCallDefaults(
                "Grok",
                PROVIDER_VERSION,
                baseUrl,
                model,
                MISSING_KEY_MESSAGE));
    }

    /**
     * Cursor route only when the xAI key is empty, the agent has no secret ref, and a Cursor user key is set.
     * An org secret ref is treated as an xAI key for {@code https://api.x.ai/v1}.
     */
    private boolean useCursor(AgentInvocationRequest request) {
        if (request.agent() != null && request.agent().secretRefId() != null) {
            return false;
        }
        if (!xaiApiKey.isBlank()) {
            return false;
        }
        return !cursorApiKey.isBlank();
    }

    private AgentInvocationResult invokeCursor(AgentInvocationRequest request) {
        if (request.apiKey() == null || request.apiKey().isBlank()) {
            return AgentInvocationResult.failure(
                    AgentInvocationStatus.FAILED,
                    "AGENT_CREDENTIAL_MISSING",
                    MISSING_KEY_MESSAGE,
                    null,
                    Map.of());
        }
        String modelId = resolveModel(request);
        Duration timeout = request.timeout() == null ? Duration.ofSeconds(60) : request.timeout();
        Instant deadline = Instant.now().plus(timeout);
        String auth = basicAuth(request.apiKey());
        try {
            ObjectNode body = objectMapper.createObjectNode();
            body.putObject("prompt").put("text", cursorPrompt(request));
            body.putObject("model").put("id", modelId);
            body.put("autoCreatePR", false);

            URI createUri = URI.create(cursorAgentsUrl);
            log.info("Grok Cursor invoke evaluation={} agent={} model={} host={}",
                    request.evaluationId(),
                    request.agent().id(),
                    modelId,
                    createUri.getHost());

            HttpResponse<String> created = send(
                    HttpRequest.newBuilder()
                            .uri(createUri)
                            .timeout(remaining(deadline))
                            .header("Content-Type", "application/json")
                            .header("Authorization", auth)
                            .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                            .build());
            if (created.statusCode() >= 400) {
                return httpFailure(created, modelId);
            }
            JsonNode createdJson = objectMapper.readTree(created.body());
            String agentId = createdJson.path("agent").path("id").asText("");
            String runId = createdJson.path("run").path("id").asText(
                    createdJson.path("agent").path("latestRunId").asText(""));
            if (!safeId(agentId) || !safeId(runId)) {
                return AgentInvocationResult.failure(
                        AgentInvocationStatus.FAILED,
                        "AGENT_ERROR",
                        "Cursor agents API returned an unexpected agent or run id",
                        modelId,
                        Map.of("baseHost", createUri.getHost()));
            }

            String agentsRoot = cursorAgentsUrl.endsWith("/") ? cursorAgentsUrl : cursorAgentsUrl + "/";
            URI runUri = URI.create(agentsRoot + agentId + "/runs/" + runId);
            JsonNode run = null;
            while (Instant.now().isBefore(deadline)) {
                HttpResponse<String> polled = send(
                        HttpRequest.newBuilder()
                                .uri(runUri)
                                .timeout(remaining(deadline))
                                .header("Authorization", auth)
                                .GET()
                                .build());
                if (polled.statusCode() >= 400) {
                    return httpFailure(polled, modelId);
                }
                run = objectMapper.readTree(polled.body());
                String status = run.path("status").asText("");
                if (isTerminal(status)) {
                    break;
                }
                Thread.sleep(POLL_INTERVAL.toMillis());
            }
            if (run == null || !isTerminal(run.path("status").asText(""))) {
                return AgentInvocationResult.failure(
                        AgentInvocationStatus.TIMED_OUT,
                        "AGENT_TIMEOUT",
                        "Cursor agents run timed out",
                        modelId,
                        Map.of("baseHost", createUri.getHost()));
            }
            String status = run.path("status").asText("");
            if (!"FINISHED".equals(status)) {
                return AgentInvocationResult.failure(
                        AgentInvocationStatus.FAILED,
                        "AGENT_ERROR",
                        SecretRedactor.sanitizeError("Cursor agents run " + status),
                        modelId,
                        Map.of("baseHost", createUri.getHost(), "runStatus", status));
            }
            String text = run.path("result").asText("");
            String patch = OpenAiCompatibleAgentProvider.extractPatch(text);
            deleteAgent(URI.create(agentsRoot + agentId), auth, deadline);
            if (patch == null || patch.isBlank()) {
                return AgentInvocationResult.failure(
                        AgentInvocationStatus.FAILED,
                        "AGENT_EMPTY_PATCH",
                        "Cursor agents run did not contain a unified diff patch",
                        modelId,
                        Map.of("baseHost", createUri.getHost(), "runStatus", status));
            }
            return AgentInvocationResult.success(
                    patch,
                    modelId,
                    CURSOR_PROVIDER_VERSION,
                    null,
                    null,
                    null,
                    null,
                    Map.of("baseHost", createUri.getHost(), "runStatus", status));
        } catch (java.net.http.HttpTimeoutException ex) {
            return AgentInvocationResult.failure(
                    AgentInvocationStatus.TIMED_OUT,
                    "AGENT_TIMEOUT",
                    "Cursor agents request timed out",
                    modelId,
                    Map.of());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return AgentInvocationResult.failure(
                    AgentInvocationStatus.CANCELLED,
                    "AGENT_INTERRUPTED",
                    "Cursor agents request interrupted",
                    modelId,
                    Map.of());
        } catch (Exception ex) {
            log.warn("Grok Cursor invoke failed evaluation={}: {}",
                    request.evaluationId(), SecretRedactor.sanitizeError(ex.getMessage()));
            return AgentInvocationResult.failure(
                    AgentInvocationStatus.FAILED,
                    "AGENT_ERROR",
                    SecretRedactor.sanitizeError(ex.getMessage()),
                    modelId,
                    Map.of());
        }
    }

    private void deleteAgent(URI agentUri, String auth, Instant deadline) {
        try {
            if (!Instant.now().isBefore(deadline)) {
                return;
            }
            send(HttpRequest.newBuilder()
                    .uri(agentUri)
                    .timeout(remaining(deadline))
                    .header("Authorization", auth)
                    .DELETE()
                    .build());
        } catch (Exception ex) {
            log.info("Grok Cursor agent cleanup skipped: {}", SecretRedactor.sanitizeError(ex.getMessage()));
        }
    }

    private HttpResponse<String> send(HttpRequest request) throws Exception {
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private AgentInvocationResult httpFailure(HttpResponse<String> response, String modelId) {
        return AgentInvocationResult.failure(
                AgentInvocationStatus.FAILED,
                "AGENT_HTTP_" + response.statusCode(),
                SecretRedactor.sanitizeError("Provider HTTP " + response.statusCode() + ": " + truncate(response.body(), 300)),
                modelId,
                Map.of("httpStatus", response.statusCode()));
    }

    private String resolveModel(AgentInvocationRequest request) {
        try {
            JsonNode config = objectMapper.readTree(
                    request.agent().configJson() == null || request.agent().configJson().isBlank()
                            ? "{}"
                            : request.agent().configJson());
            String configured = config.path("model").asText("");
            if (!configured.isBlank()) {
                return configured.trim();
            }
        } catch (Exception ignored) {
            // fall through to the env default
        }
        return model;
    }

    private static String cursorPrompt(AgentInvocationRequest request) {
        StringBuilder sb = new StringBuilder();
        sb.append("""
                You are a coding agent evaluated by AgentBench.
                Return ONLY a unified diff patch that fixes the task.
                Do not claim test results. Do not include explanations outside a diff/patch fence.

                """);
        sb.append("Task prompt:\n").append(request.taskPrompt() == null ? "" : request.taskPrompt()).append("\n\n");
        if (request.visibleContextJson() != null && !request.visibleContextJson().isBlank()) {
            sb.append("Visible context (JSON, no hidden tests):\n")
                    .append(request.visibleContextJson())
                    .append("\n\n");
        }
        sb.append("Return a unified diff patch only.");
        return sb.toString();
    }

    private static boolean safeId(String id) {
        return id != null
                && !id.isBlank()
                && id.chars().allMatch(ch -> Character.isLetterOrDigit(ch) || ch == '-' || ch == '_');
    }

    private static boolean isTerminal(String status) {
        return "FINISHED".equals(status) || "ERROR".equals(status)
                || "CANCELLED".equals(status) || "EXPIRED".equals(status);
    }

    private static Duration remaining(Instant deadline) {
        Duration left = Duration.between(Instant.now(), deadline);
        if (left.isNegative() || left.isZero()) {
            return Duration.ofMillis(1);
        }
        return left;
    }

    /** HTTP Basic with the key as the username and an empty password. Never log the result. */
    static String basicAuth(String apiKey) {
        String token = Base64.getEncoder().encodeToString((apiKey + ":").getBytes(StandardCharsets.UTF_8));
        return "Basic " + token;
    }

    private static String blankTo(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.trim();
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
