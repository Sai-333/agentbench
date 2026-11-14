package com.agentbench.agents.domain;

import java.util.Locale;
import java.util.Set;

/**
 * Supported agent adapter kinds. Domain vocabulary — no vendor SDK types.
 */
public enum AdapterType {
    FAKE,
    /** OpenAI-compatible Chat Completions HTTP API (first real provider in M5). */
    OPENAI_COMPATIBLE,
    /** Generic HTTP webhook-style agent endpoint. */
    HTTP,
    /** Coding-agent CLI adapter — runs via RuntimeIsolator (Docker/gVisor/local). */
    CODING_CLI,
    /**
     * Default model provider. OpenAI-compatible Chat Completions against the xAI API
     * ({@code https://api.x.ai/v1}). Model from {@code GROK_MODEL} (default {@code grok-4.7};
     * legacy Cursor keys also include {@code grok-4.6} and {@code grok-4.5}). Key from an org
     * secret ref, else {@code XAI_API_KEY}, else {@code CURSOR_API_KEY}.
     */
    GROK,
    /**
     * Legacy alias accepted at API boundary; persisted/normalized to {@link #OPENAI_COMPATIBLE}.
     */
    SPRING_AI;

    public static final Set<String> PERSISTED = Set.of(
            FAKE.name(),
            OPENAI_COMPATIBLE.name(),
            HTTP.name(),
            CODING_CLI.name(),
            GROK.name(),
            SPRING_AI.name()
    );

    public AdapterType normalize() {
        return this == SPRING_AI ? OPENAI_COMPATIBLE : this;
    }

    public static AdapterType fromString(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("adapterType is required");
        }
        try {
            return AdapterType.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Unsupported adapterType: " + value);
        }
    }
}
