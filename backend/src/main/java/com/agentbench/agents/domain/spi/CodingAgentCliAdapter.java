package com.agentbench.agents.domain.spi;

/**
 * Coding-agent CLI adapter interface. Full process isolation belongs with later execution
 * hardening; M5 ships the SPI + a stub implementation.
 */
public interface CodingAgentCliAdapter {

    /**
     * Invoke an external coding agent CLI (cursor-agent, claude-code, aider, …).
     *
     * @param request provider-independent request (no secrets in logs)
     * @param command resolved CLI command template from agent config
     * @param workingDirectory workspace path when available; may be null in stub mode
     */
    AgentInvocationResult invokeCli(
            AgentInvocationRequest request,
            String command,
            String workingDirectory
    );
}
