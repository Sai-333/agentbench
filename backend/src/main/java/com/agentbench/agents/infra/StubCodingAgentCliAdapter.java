package com.agentbench.agents.infra;

import com.agentbench.agents.domain.AdapterType;
import com.agentbench.agents.domain.spi.AgentInvocationRequest;
import com.agentbench.agents.domain.spi.AgentInvocationResult;
import com.agentbench.agents.domain.spi.AgentInvocationStatus;
import com.agentbench.agents.domain.spi.AgentProvider;
import com.agentbench.agents.domain.spi.CodingAgentCliAdapter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Skeleton CLI coding-agent adapter. Full process isolation / sandboxing is deferred
 * (execution hardening). M5 registers the SPI and returns NOT_IMPLEMENTED unless a
 * deterministic fixture patch is configured for local demos.
 */
@Component
public class StubCodingAgentCliAdapter implements CodingAgentCliAdapter, AgentProvider {

    public static final String PROVIDER_VERSION = "coding-cli-stub-v1";

    private final ObjectMapper objectMapper;

    public StubCodingAgentCliAdapter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public String adapterType() {
        return AdapterType.CODING_CLI.name();
    }

    @Override
    public AgentInvocationResult invoke(AgentInvocationRequest request) {
        JsonNode config = readConfig(request.agent().configJson());
        String command = config.path("command").asText("coding-agent");
        return invokeCli(request, command, null);
    }

    @Override
    public AgentInvocationResult invokeCli(
            AgentInvocationRequest request,
            String command,
            String workingDirectory) {
        JsonNode config = readConfig(request.agent().configJson());
        // Deterministic escape hatch for CI without spawning processes.
        if (config.hasNonNull("patch")) {
            return AgentInvocationResult.success(
                    config.get("patch").asText(),
                    config.path("model").asText("cli-stub"),
                    PROVIDER_VERSION,
                    null, null, null, null,
                    Map.of(
                            "mode", "fixture-patch",
                            "command", command == null ? "" : command,
                            "isolation", "deferred"
                    )
            );
        }
        return AgentInvocationResult.failure(
                AgentInvocationStatus.NOT_IMPLEMENTED,
                "CODING_CLI_NOT_IMPLEMENTED",
                "Coding CLI adapter is a skeleton in M5; configure config.patch for demos or wait for execution isolation",
                config.path("model").asText(null),
                Map.of(
                        "command", command == null ? "" : command,
                        "workingDirectory", workingDirectory == null ? "" : workingDirectory,
                        "isolation", "deferred"
                )
        );
    }

    private JsonNode readConfig(String configJson) {
        try {
            return objectMapper.readTree(configJson == null || configJson.isBlank() ? "{}" : configJson);
        } catch (Exception ex) {
            return objectMapper.createObjectNode();
        }
    }
}
