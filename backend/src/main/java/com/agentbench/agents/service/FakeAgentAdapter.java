package com.agentbench.agents.service;

import com.agentbench.agents.domain.Agent;
import com.agentbench.agents.domain.spi.AgentInvocationRequest;
import com.agentbench.agents.domain.spi.AgentInvocationResult;
import com.agentbench.agents.infra.FakeAgentProvider;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.UUID;

/**
 * Backward-compatible façade over {@link FakeAgentProvider} for callers that still use collectPatch.
 * Prefer {@link AgentInvocationService} for evaluation orchestration.
 */
@Component
public class FakeAgentAdapter {

    private final FakeAgentProvider fakeAgentProvider;

    public FakeAgentAdapter(FakeAgentProvider fakeAgentProvider) {
        this.fakeAgentProvider = fakeAgentProvider;
    }

    public String collectPatch(Agent agent, JsonNode taskSnapshot) {
        String reference = null;
        if (taskSnapshot != null && taskSnapshot.hasNonNull("referencePatch")) {
            reference = taskSnapshot.get("referencePatch").asText("");
        }
        String prompt = taskSnapshot != null && taskSnapshot.hasNonNull("prompt")
                ? taskSnapshot.get("prompt").asText("")
                : "";
        AgentInvocationRequest request = new AgentInvocationRequest(
                UUID.randomUUID(),
                agent.orgId(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                agent,
                prompt,
                null,
                reference,
                null,
                Duration.ofSeconds(30),
                1
        );
        AgentInvocationResult result = fakeAgentProvider.invoke(request);
        return result.patchContent();
    }
}
