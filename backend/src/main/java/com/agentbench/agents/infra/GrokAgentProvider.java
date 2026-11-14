package com.agentbench.agents.infra;

import com.agentbench.agents.domain.AdapterType;
import com.agentbench.agents.domain.spi.AgentInvocationRequest;
import com.agentbench.agents.domain.spi.AgentInvocationResult;
import com.agentbench.agents.domain.spi.AgentProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Default model provider. OpenAI-compatible Chat Completions at {@code https://api.x.ai/v1}.
 * Model: {@code GROK_MODEL} (default {@code grok-4.7}).
 * Key: org secret ref, else {@code XAI_API_KEY}, else {@code CURSOR_API_KEY}
 * (a Cursor legacy user API key, which on a legacy Cursor plan can run Grok 4.7, 4.6, and 4.5).
 * The API key is never logged.
 */
@Component
public class GrokAgentProvider implements AgentProvider {

    public static final String PROVIDER_VERSION = "grok-v1";

    /** Stable name of the org agent used when an evaluation omits {@code agentId}. */
    public static final String DEFAULT_AGENT_NAME = "Grok";

    /** xAI API base currently documented for OpenAI-compatible chat completions. */
    public static final String DEFAULT_BASE_URL = "https://api.x.ai/v1";

    /**
     * Flagship Grok model served by the xAI API and included for legacy Cursor API keys.
     * Override with {@code GROK_MODEL}.
     */
    public static final String DEFAULT_MODEL = "grok-4.7";

    /**
     * Grok model ids a legacy Cursor plan includes in its Cursor Models pool
     * (also the ids the xAI API serves for this family). {@link #DEFAULT_MODEL} is first.
     */
    public static final List<String> LEGACY_CURSOR_GROK_MODELS = List.of(
            "grok-4.7",
            "grok-4.6",
            "grok-4.5");

    public static final String MISSING_KEY_MESSAGE =
            "Grok agent requires an API key. Set XAI_API_KEY or CURSOR_API_KEY, or store one in an org secret_ref";

    private final OpenAiCompatibleAgentProvider chat;
    private final String baseUrl;
    private final String model;

    @Autowired
    public GrokAgentProvider(
            OpenAiCompatibleAgentProvider chat,
            @Value("${agentbench.agents.grok.base-url:" + DEFAULT_BASE_URL + "}") String baseUrl,
            @Value("${agentbench.agents.grok.model:" + DEFAULT_MODEL + "}") String model) {
        this.chat = chat;
        this.baseUrl = blankTo(baseUrl, DEFAULT_BASE_URL);
        this.model = blankTo(model, DEFAULT_MODEL);
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

    /**
     * Env key for the default Grok agent when no org secret ref is set.
     * {@code XAI_API_KEY} wins because the chat call goes to the xAI host.
     * {@code CURSOR_API_KEY} is the legacy Cursor user key and is used only when the xAI key is unset.
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
        return chat.complete(request, new ChatCallDefaults(
                "Grok",
                PROVIDER_VERSION,
                baseUrl,
                model,
                MISSING_KEY_MESSAGE));
    }

    private static String blankTo(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.trim();
    }
}
