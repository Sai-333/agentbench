package com.agentbench.agents.infra;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SecretRedactorTest {

    @Test
    void redactsBearerAndSkKeys() {
        String input = "Authorization: Bearer sk-abc1234567890xyz error";
        String out = SecretRedactor.redact(input);
        assertFalse(out.contains("sk-abc1234567890xyz"));
        assertTrue(out.contains("[REDACTED]") || out.contains("sk-[REDACTED]"));
    }

    @Test
    void redactsJsonApiKeyFields() {
        String input = "{\"api_key\":\"super-secret-value\",\"model\":\"gpt\"}";
        String out = SecretRedactor.redact(input);
        assertFalse(out.contains("super-secret-value"));
        assertTrue(out.contains("[REDACTED]"));
    }

    @Test
    void detectsSecretLikeConfigKeys() {
        assertTrue(SecretRedactor.looksLikeSecretKey("apiKey"));
        assertTrue(SecretRedactor.looksLikeSecretKey("openai_api_key"));
        assertFalse(SecretRedactor.looksLikeSecretKey("baseUrl"));
        assertFalse(SecretRedactor.looksLikeSecretKey("model"));
        assertFalse(SecretRedactor.looksLikeSecretKey("costPer1kPromptTokens"));
        assertFalse(SecretRedactor.looksLikeSecretKey("maxTokens"));
    }
}
