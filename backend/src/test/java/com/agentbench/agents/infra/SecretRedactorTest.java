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
    void redactsAwsAccessKey() {
        String input = "AKIAIOSFODNN7EXAMPLE in log";
        String out = SecretRedactor.redact(input);
        assertFalse(out.contains("AKIAIOSFODNN7EXAMPLE"));
    }

    @Test
    void redactsJwt() {
        String jwt = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9."
                + "eyJzdWIiOiIxMjM0NTY3ODkwIiwibmFtZSI6IkpvaG4ifQ."
                + "SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c";
        String out = SecretRedactor.redact("token " + jwt);
        assertFalse(out.contains(jwt));
        assertTrue(out.contains("[REDACTED:jwt]"));
    }

    @Test
    void redactsPrivateKeyPem() {
        String input = "-----BEGIN PRIVATE KEY-----\nABC\n-----END PRIVATE KEY-----";
        String out = SecretRedactor.redact(input);
        assertFalse(out.contains("BEGIN PRIVATE KEY"));
        assertTrue(out.contains("[REDACTED:private_key]"));
    }

    @Test
    void redactsEnvPasswordAssignment() {
        String out = SecretRedactor.redact("password=super-secret-value");
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
