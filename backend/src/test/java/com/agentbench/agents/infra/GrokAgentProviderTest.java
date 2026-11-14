package com.agentbench.agents.infra;

import com.agentbench.agents.domain.Agent;
import com.agentbench.agents.domain.spi.AgentInvocationRequest;
import com.agentbench.agents.domain.spi.AgentInvocationResult;
import com.agentbench.agents.domain.spi.AgentInvocationStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GrokAgentProviderTest {

    private static final String FAKE_KEY = "xai-test-not-a-real-key";

    @RegisterExtension
    static WireMockExtension wireMock = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void resetWireMock() {
        wireMock.resetAll();
    }

    @Test
    void documentedDefaultsMatchTheXaiAndLegacyCursorCatalog() {
        assertEquals("https://api.x.ai/v1", GrokAgentProvider.DEFAULT_BASE_URL);
        assertEquals("grok-4.7", GrokAgentProvider.DEFAULT_MODEL);
        assertEquals("grok-4.7", GrokAgentProvider.LEGACY_CURSOR_GROK_MODELS.get(0));
        assertTrue(GrokAgentProvider.LEGACY_CURSOR_GROK_MODELS.contains("grok-4.6"));
        assertTrue(GrokAgentProvider.LEGACY_CURSOR_GROK_MODELS.contains("grok-4.5"));
        String message = SecretRedactor.sanitizeError(GrokAgentProvider.MISSING_KEY_MESSAGE);
        assertTrue(message.contains("XAI_API_KEY"));
        assertTrue(message.contains("CURSOR_API_KEY"));
    }

    @Test
    void envKeyPrefersXaiThenCursorLegacyKey() {
        assertEquals("xai-env", GrokAgentProvider.selectEnvKey("xai-env", "cursor-env"));
        assertEquals("cursor-env", GrokAgentProvider.selectEnvKey("  ", "cursor-env"));
        assertEquals("cursor-env", GrokAgentProvider.selectEnvKey(null, " cursor-env "));
        assertNull(GrokAgentProvider.selectEnvKey(null, " "));
    }

    @Test
    void callsChatCompletionsWithDefaultGrokModel() {
        String patch = "diff --git a/src/Bug.java b/src/Bug.java\n--- a/src/Bug.java\n+++ b/src/Bug.java\n@@ -1 +1 @@\n-old\n+new\n";
        wireMock.stubFor(post(urlEqualTo("/v1/chat/completions"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                  "id": "chatcmpl-grok",
                                  "choices": [{"finish_reason": "stop", "message": {"role": "assistant", "content": %s}}],
                                  "usage": {"prompt_tokens": 3, "completion_tokens": 4, "total_tokens": 7}
                                }
                                """.formatted(json(patch)))));

        GrokAgentProvider provider = provider(wireMock.baseUrl() + "/v1", GrokAgentProvider.DEFAULT_MODEL);
        Agent agent = agent("{}", null);
        AgentInvocationResult result = provider.invoke(request(agent, FAKE_KEY));

        assertEquals(AgentInvocationStatus.SUCCEEDED, result.status());
        assertTrue(result.patchContent().contains("diff --git"));
        assertEquals("grok-4.7", result.model());
        assertEquals(GrokAgentProvider.PROVIDER_VERSION, result.providerVersion());
        assertFalse(String.valueOf(result.metadata()).contains(FAKE_KEY));

        wireMock.verify(postRequestedFor(urlEqualTo("/v1/chat/completions"))
                .withHeader("Authorization", equalTo("Bearer " + FAKE_KEY))
                .withRequestBody(containing("\"model\":\"grok-4.7\""))
                .withRequestBody(containing("Fix the bug")));
    }

    @Test
    void modelOverrideAndCursorKeyAreNotLoggedInTheResult() {
        wireMock.stubFor(post(urlEqualTo("/v1/chat/completions"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"id":"chatcmpl-grok-46","choices":[{"finish_reason":"stop","message":{"content":"diff --git a/x b/x\\n"}}],"usage":{}}
                                """)));
        String cursorKey = "crsr_testnotarealkey0123456789";
        GrokAgentProvider provider = provider(wireMock.baseUrl(), "grok-4.7");
        Agent agent = agent("{\"model\":\"grok-4.6\",\"baseUrl\":\"" + wireMock.baseUrl() + "\"}", null);
        AgentInvocationResult result = provider.invoke(request(agent, cursorKey));

        assertEquals(AgentInvocationStatus.SUCCEEDED, result.status());
        assertEquals("grok-4.6", result.model());
        assertFalse(String.valueOf(result.metadata()).contains(cursorKey));
        wireMock.verify(postRequestedFor(urlEqualTo("/v1/chat/completions"))
                .withHeader("Authorization", equalTo("Bearer " + cursorKey))
                .withRequestBody(containing("\"model\":\"grok-4.6\"")));
    }

    @Test
    void missingKeyNamesBothEnvVarsAndDoesNotCallTheNetwork() {
        GrokAgentProvider provider = provider(wireMock.baseUrl(), "grok-4.7");
        AgentInvocationResult result = provider.invoke(request(agent("{}", null), null));
        assertEquals(AgentInvocationStatus.FAILED, result.status());
        assertEquals("AGENT_CREDENTIAL_MISSING", result.errorCode());
        assertTrue(result.errorMessage().contains("XAI_API_KEY"));
        assertTrue(result.errorMessage().contains("CURSOR_API_KEY"));
        assertEquals(0, wireMock.getAllServeEvents().size());
    }

    @Test
    void fakeProviderStillSucceedsWithoutAKey() {
        FakeAgentProvider fake = new FakeAgentProvider(objectMapper);
        Agent agent = new Agent(
                UUID.randomUUID(), UUID.randomUUID(), "fake", "FAKE",
                null, "{}", true, UUID.randomUUID(), Instant.now(), Instant.now());
        AgentInvocationResult result = fake.invoke(request(agent, null));
        assertEquals(AgentInvocationStatus.SUCCEEDED, result.status());
        assertNotNull(result.patchContent());
        assertTrue(result.patchContent().contains("diff --git"));
    }

    private GrokAgentProvider provider(String baseUrl, String model) {
        OpenAiCompatibleAgentProvider chat = new OpenAiCompatibleAgentProvider(
                objectMapper,
                java.net.http.HttpClient.newHttpClient(),
                "https://api.openai.com",
                Duration.ofSeconds(10));
        return new GrokAgentProvider(chat, baseUrl, model);
    }

    private Agent agent(String config, UUID secretRef) {
        return new Agent(
                UUID.randomUUID(), UUID.randomUUID(), "grok", "GROK",
                secretRef, config, true, UUID.randomUUID(), Instant.now(), Instant.now());
    }

    private AgentInvocationRequest request(Agent agent, String apiKey) {
        return new AgentInvocationRequest(
                UUID.randomUUID(), agent.orgId(), UUID.randomUUID(), UUID.randomUUID(),
                agent, "Fix the bug", "{\"visible\":true}", null,
                apiKey, Duration.ofSeconds(10), 1);
    }

    private String json(String value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
