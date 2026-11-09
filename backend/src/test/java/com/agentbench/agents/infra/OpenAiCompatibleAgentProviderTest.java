package com.agentbench.agents.infra;

import com.agentbench.agents.domain.Agent;
import com.agentbench.agents.domain.spi.AgentInvocationRequest;
import com.agentbench.agents.domain.spi.AgentInvocationResult;
import com.agentbench.agents.domain.spi.AgentInvocationStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenAiCompatibleAgentProviderTest {

    @RegisterExtension
    static WireMockExtension wireMock = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void extractsPatchFromChatCompletion() {
        String patch = """
                diff --git a/src/Bug.java b/src/Bug.java
                --- a/src/Bug.java
                +++ b/src/Bug.java
                @@ -1 +1 @@
                -old
                +new
                """;
        String body = """
                {
                  "id": "chatcmpl-test",
                  "choices": [
                    {
                      "finish_reason": "stop",
                      "message": {
                        "role": "assistant",
                        "content": "```diff\\n%s\\n```"
                      }
                    }
                  ],
                  "usage": { "prompt_tokens": 11, "completion_tokens": 22, "total_tokens": 33 }
                }
                """.formatted(patch.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n"));

        wireMock.stubFor(post(urlEqualTo("/v1/chat/completions"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(body)));

        OpenAiCompatibleAgentProvider provider = new OpenAiCompatibleAgentProvider(
                objectMapper,
                java.net.http.HttpClient.newHttpClient(),
                wireMock.baseUrl(),
                Duration.ofSeconds(10)
        );

        Agent agent = new Agent(
                UUID.randomUUID(), UUID.randomUUID(), "openai-agent", "OPENAI_COMPATIBLE",
                UUID.randomUUID(),
                "{\"model\":\"gpt-test\",\"baseUrl\":\"" + wireMock.baseUrl() + "\",\"costPer1kPromptTokens\":0.01,\"costPer1kCompletionTokens\":0.02}",
                true, UUID.randomUUID(), Instant.now(), Instant.now()
        );

        AgentInvocationResult result = provider.invoke(new AgentInvocationRequest(
                UUID.randomUUID(), agent.orgId(), UUID.randomUUID(), UUID.randomUUID(),
                agent, "Fix the bug", "{\"visible\":true}", null,
                "sk-test-key-not-real", Duration.ofSeconds(10), 1
        ));

        assertEquals(AgentInvocationStatus.SUCCEEDED, result.status());
        assertNotNull(result.patchContent());
        assertTrue(result.patchContent().contains("diff --git"));
        assertEquals(11, result.promptTokens());
        assertEquals(22, result.completionTokens());
        assertEquals(33, result.totalTokens());
        assertNotNull(result.estimatedCostUsd());
        assertEquals(OpenAiCompatibleAgentProvider.PROVIDER_VERSION, result.providerVersion());

        wireMock.verify(postRequestedFor(urlEqualTo("/v1/chat/completions"))
                .withHeader("Authorization", equalTo("Bearer sk-test-key-not-real"))
                .withRequestBody(containing("Fix the bug")));
    }

    @Test
    void failsWhenApiKeyMissing() {
        OpenAiCompatibleAgentProvider provider = new OpenAiCompatibleAgentProvider(
                objectMapper,
                java.net.http.HttpClient.newHttpClient(),
                wireMock.baseUrl(),
                Duration.ofSeconds(5)
        );
        Agent agent = new Agent(
                UUID.randomUUID(), UUID.randomUUID(), "openai-agent", "OPENAI_COMPATIBLE",
                null, "{\"model\":\"gpt-test\"}", true, UUID.randomUUID(), Instant.now(), Instant.now()
        );
        AgentInvocationResult result = provider.invoke(new AgentInvocationRequest(
                UUID.randomUUID(), agent.orgId(), UUID.randomUUID(), UUID.randomUUID(),
                agent, "prompt", null, null, null, Duration.ofSeconds(5), 1
        ));
        assertEquals(AgentInvocationStatus.FAILED, result.status());
        assertEquals("AGENT_CREDENTIAL_MISSING", result.errorCode());
    }

    @Test
    void extractPatchHelpers() {
        assertTrue(OpenAiCompatibleAgentProvider.extractPatch("```diff\ndiff --git a/x b/x\n```")
                .startsWith("diff --git"));
        assertEquals(
                "diff --git a/x b/x",
                OpenAiCompatibleAgentProvider.extractPatch("Here:\ndiff --git a/x b/x")
        );
    }
}
