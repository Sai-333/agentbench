package com.agentbench.agents.infra;

/**
 * Per-call defaults for the shared OpenAI-compatible chat client.
 * Holds no credentials.
 */
record ChatCallDefaults(
        String logLabel,
        String providerVersion,
        String defaultBaseUrl,
        String defaultModel,
        String missingCredentialMessage
) {
}
