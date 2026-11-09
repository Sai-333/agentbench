package com.agentbench.agents.service;

import com.agentbench.agents.domain.Agent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

/**
 * M4/M5 stub agent adapter: returns a configured patch or the task reference patch.
 * Never claims test results.
 */
@Component
public class FakeAgentAdapter {

    private final ObjectMapper objectMapper;

    public FakeAgentAdapter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String collectPatch(Agent agent, JsonNode taskSnapshot) {
        try {
            JsonNode config = objectMapper.readTree(agent.configJson() == null ? "{}" : agent.configJson());
            if (config.hasNonNull("patch")) {
                return config.get("patch").asText();
            }
        } catch (Exception ignored) {
            // fall through to reference patch
        }
        if (taskSnapshot != null && taskSnapshot.hasNonNull("referencePatch")) {
            String reference = taskSnapshot.get("referencePatch").asText("");
            if (!reference.isBlank()) {
                return reference;
            }
        }
        // Minimal no-op style patch against README if nothing else is configured.
        return """
                diff --git a/README.md b/README.md
                --- a/README.md
                +++ b/README.md
                @@ -1 +1,2 @@
                 AgentBench evaluation workspace
                +Patched by FakeAgentAdapter
                """;
    }
}
