package com.agentbench.agents.infra;

import com.agentbench.agents.domain.AdapterType;
import com.agentbench.agents.domain.spi.AgentInvocationRequest;
import com.agentbench.agents.domain.spi.AgentInvocationResult;
import com.agentbench.agents.domain.spi.AgentProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Deterministic Fake/Mock provider for tests and CI — no external API calls.
 */
@Component
public class FakeAgentProvider implements AgentProvider {

    public static final String PROVIDER_VERSION = "fake-v1";

    private final ObjectMapper objectMapper;

    public FakeAgentProvider(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public String adapterType() {
        return AdapterType.FAKE.name();
    }

    @Override
    public AgentInvocationResult invoke(AgentInvocationRequest request) {
        try {
            JsonNode config = objectMapper.readTree(
                    request.agent().configJson() == null ? "{}" : request.agent().configJson());
            if (config.path("fail").asBoolean(false)) {
                return AgentInvocationResult.failure(
                        com.agentbench.agents.domain.spi.AgentInvocationStatus.FAILED,
                        "FAKE_FORCED_FAILURE",
                        "Fake provider configured to fail",
                        "fake",
                        Map.of("deterministic", true, "source", "FAKE")
                );
            }
        } catch (Exception ignored) {
            // fall through to patch resolution
        }
        String patch = resolvePatch(request);
        return AgentInvocationResult.success(
                patch,
                "fake",
                PROVIDER_VERSION,
                null,
                null,
                null,
                null,
                Map.of("deterministic", true, "source", "FAKE")
        );
    }

    private String resolvePatch(AgentInvocationRequest request) {
        try {
            JsonNode config = objectMapper.readTree(
                    request.agent().configJson() == null ? "{}" : request.agent().configJson());
            if (config.hasNonNull("patch")) {
                return config.get("patch").asText();
            }
        } catch (Exception ignored) {
            // fall through
        }
        if (request.referencePatchHint() != null && !request.referencePatchHint().isBlank()) {
            return request.referencePatchHint();
        }
        return """
                diff --git a/README.md b/README.md
                --- a/README.md
                +++ b/README.md
                @@ -1 +1,2 @@
                 AgentBench evaluation workspace
                +Patched by FakeAgentProvider
                """;
    }
}
