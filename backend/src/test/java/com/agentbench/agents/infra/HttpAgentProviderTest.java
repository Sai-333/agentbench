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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * REG-HTTP-AGENT: webhook POST, JSON {@code patch}, redirects never followed,
 * timeout, and fail-closed when the body has no patch.
 */
class HttpAgentProviderTest {

    private static final String FAKE_KEY = "webhook-test-key-not-real";

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
    void postsWebhookAndReadsJsonPatch() {
        String patch = """
                diff --git a/src/Bug.java b/src/Bug.java
                --- a/src/Bug.java
                +++ b/src/Bug.java
                @@ -1 +1 @@
                -old
                +new
                """;
        wireMock.stubFor(post(urlEqualTo("/hook"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"patch":%s,"model":"ignored","usage":{"promptTokens":3,"completionTokens":4,"totalTokens":7}}
                                """.formatted(json(patch)))));

        HttpAgentProvider provider = provider();
        AgentInvocationResult result = provider.invoke(request(agent(
                "{\"endpoint\":\"" + wireMock.baseUrl() + "/hook\",\"model\":\"webhook-model\"}"), FAKE_KEY));

        assertEquals(AgentInvocationStatus.SUCCEEDED, result.status());
        assertTrue(result.patchContent().contains("diff --git"));
        assertEquals("webhook-model", result.model());
        assertEquals(HttpAgentProvider.PROVIDER_VERSION, result.providerVersion());
        assertEquals(3, result.promptTokens());
        assertEquals(4, result.completionTokens());
        assertEquals(7, result.totalTokens());
        assertFalse(String.valueOf(result.metadata()).contains(FAKE_KEY));
        assertEquals("localhost", result.metadata().get("endpointHost"));

        wireMock.verify(postRequestedFor(urlEqualTo("/hook"))
                .withHeader("Authorization", equalTo("Bearer " + FAKE_KEY))
                .withHeader("Content-Type", equalTo("application/json"))
                .withRequestBody(containing("\"taskPrompt\":\"Fix the bug\""))
                .withRequestBody(containing("\"visibleContext\"")));
    }

    @Test
    void neverFollowsRedirects() {
        wireMock.stubFor(post(urlEqualTo("/hook"))
                .willReturn(aResponse()
                        .withStatus(302)
                        .withHeader("Location", wireMock.baseUrl() + "/followed")
                        .withHeader("Content-Type", "application/json")
                        .withBody("{}")));
        wireMock.stubFor(post(urlEqualTo("/followed"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"patch\":\"diff --git a/x b/x\\n\"}")));

        AgentInvocationResult result = provider().invoke(request(
                agent("{\"endpoint\":\"" + wireMock.baseUrl() + "/hook\"}"), null));

        assertEquals(AgentInvocationStatus.FAILED, result.status());
        assertEquals("AGENT_EMPTY_PATCH", result.errorCode());
        wireMock.verify(1, postRequestedFor(urlEqualTo("/hook")));
        wireMock.verify(0, postRequestedFor(urlEqualTo("/followed")));
    }

    @Test
    void timesOut() {
        wireMock.stubFor(post(urlEqualTo("/hook"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withFixedDelay(5_000)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"patch\":\"diff --git a/x b/x\\n\"}")));

        Agent agent = agent("{\"endpoint\":\"" + wireMock.baseUrl() + "/hook\"}");
        AgentInvocationResult result = provider().invoke(new AgentInvocationRequest(
                UUID.randomUUID(), agent.orgId(), UUID.randomUUID(), UUID.randomUUID(),
                agent, "Fix the bug", null, null, null, Duration.ofMillis(400), 1));

        assertEquals(AgentInvocationStatus.TIMED_OUT, result.status());
        assertEquals("AGENT_TIMEOUT", result.errorCode());
        assertFalse(String.valueOf(result.errorMessage()).contains("diff --git"));
    }

    @Test
    void failsClosedWhenBodyHasNoPatch() {
        wireMock.stubFor(post(urlEqualTo("/hook"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"ok\":true,\"patch\":\"  \"}")));

        AgentInvocationResult blank = provider().invoke(request(
                agent("{\"endpoint\":\"" + wireMock.baseUrl() + "/hook\"}"), null));
        assertEquals(AgentInvocationStatus.FAILED, blank.status());
        assertEquals("AGENT_EMPTY_PATCH", blank.errorCode());
        assertNull(blank.patchContent());

        wireMock.resetAll();
        wireMock.stubFor(post(urlEqualTo("/hook"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"ok\":true}")));
        AgentInvocationResult missing = provider().invoke(request(
                agent("{\"endpoint\":\"" + wireMock.baseUrl() + "/hook\"}"), null));
        assertEquals(AgentInvocationStatus.FAILED, missing.status());
        assertEquals("AGENT_EMPTY_PATCH", missing.errorCode());
        assertNull(missing.patchContent());
    }

    @Test
    void httpErrorDoesNotAcceptAPatchInTheErrorBody() {
        wireMock.stubFor(post(urlEqualTo("/hook"))
                .willReturn(aResponse()
                        .withStatus(503)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"patch\":\"diff --git a/x b/x\\n\",\"error\":\"" + FAKE_KEY + "\"}")));

        AgentInvocationResult result = provider().invoke(request(
                agent("{\"endpoint\":\"" + wireMock.baseUrl() + "/hook\"}"), FAKE_KEY));

        assertEquals(AgentInvocationStatus.FAILED, result.status());
        assertEquals("AGENT_HTTP_503", result.errorCode());
        assertNull(result.patchContent());
        assertFalse(String.valueOf(result.errorMessage()).contains(FAKE_KEY));
        assertEquals(503, result.metadata().get("httpStatus"));
    }

    @Test
    void failsWhenEndpointMissing() {
        AgentInvocationResult result = provider().invoke(request(agent("{}"), null));
        assertEquals(AgentInvocationStatus.FAILED, result.status());
        assertEquals("AGENT_CONFIG_INVALID", result.errorCode());
        assertEquals(0, wireMock.getAllServeEvents().size());
    }

    private HttpAgentProvider provider() {
        return new HttpAgentProvider(objectMapper, 5);
    }

    private Agent agent(String configJson) {
        return new Agent(
                UUID.randomUUID(), UUID.randomUUID(), "http-agent", "HTTP",
                null, configJson, true, UUID.randomUUID(), Instant.now(), Instant.now());
    }

    private AgentInvocationRequest request(Agent agent, String apiKey) {
        return new AgentInvocationRequest(
                UUID.randomUUID(), agent.orgId(), UUID.randomUUID(), UUID.randomUUID(),
                agent, "Fix the bug", "{\"visible\":true}", null,
                apiKey, Duration.ofSeconds(5), 1);
    }

    private String json(String value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
