package com.agentbench.agents.domain.spi;

/**
 * Provider-independent agent adapter SPI (ADR-003).
 * Concrete adapters live under {@code agents.infra}; evaluation.domain must not depend on vendor SDKs.
 */
public interface AgentProvider {

    /** Canonical adapter type this provider handles (e.g. {@code OPENAI_COMPATIBLE}). */
    String adapterType();

    AgentInvocationResult invoke(AgentInvocationRequest request);
}
