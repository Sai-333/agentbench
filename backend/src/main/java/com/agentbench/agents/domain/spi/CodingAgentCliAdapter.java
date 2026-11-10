package com.agentbench.agents.domain.spi;

/**
 * Coding-agent CLI adapter interface. Implementations must invoke the CLI through
 * {@code RuntimeIsolator} / {@code ExecutionIsolatorService} — never a raw host shell —
 * except for the deterministic {@code config.patch} fixture path used in CI demos.
 */
public interface CodingAgentCliAdapter {

    /**
     * Invoke an external coding agent CLI (cursor-agent, claude-code, aider, …).
     *
     * @param request provider-independent request (no secrets in logs)
     * @param command resolved CLI command template / binary from agent config
     * @param workingDirectory workspace path when available; may be null only for fixture-patch mode
     */
    AgentInvocationResult invokeCli(
            AgentInvocationRequest request,
            String command,
            String workingDirectory
    );
}
