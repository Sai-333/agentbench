package com.agentbench.agents;

import com.agentbench.evaluation.service.EvaluationSyncRunner;
import com.agentbench.support.PostgresTestSupport;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Default Grok agent: omitted provider/agent id, env key not echoed, FAKE still runs.
 * The WireMock key is a fixture stand-in, not a credential.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class GrokDefaultAgentIntegrationTest {

    private static final String FIXTURE_KEY = "xai-wiremock-not-a-real-key";

    private static final String FIXTURE = """
            {
              "workspace": {
                "src/Bug.java": "class Bug { String value; String get() { return value; } }\\n"
              },
              "allowedPaths": ["src/"],
              "cases": [
                {
                  "key": "target.nullSafe",
                  "role": "target",
                  "baselineChecks": [
                    {"type": "file_contains", "path": "src/Bug.java", "needle": "Objects.requireNonNull", "expectPresent": true}
                  ],
                  "postChecks": [
                    {"type": "file_contains", "path": "src/Bug.java", "needle": "Objects.requireNonNull", "expectPresent": true}
                  ]
                }
              ]
            }
            """;

    private static final String REFERENCE_PATCH = """
            diff --git a/src/Bug.java b/src/Bug.java
            --- a/src/Bug.java
            +++ b/src/Bug.java
            @@ -1 +1 @@
            -class Bug { String value; String get() { return value; } }
            +class Bug { String value; String get() { return java.util.Objects.requireNonNull(value); } }
            """;

    @RegisterExtension
    static WireMockExtension wireMock = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    @Container
    static PostgreSQLContainer<?> postgres = PostgresTestSupport.postgres();

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        PostgresTestSupport.registerRlsAwareDatasource(registry, postgres);
        registry.add("agentbench.evaluation.listener-enabled", () -> "false");
        registry.add("agentbench.agents.retry-backoff-ms", () -> "0");
        registry.add("agentbench.agents.grok.model", () -> "grok-test");
        registry.add("agentbench.agents.grok.api-key", () -> FIXTURE_KEY);
        registry.add("agentbench.agents.grok.cursor-api-key", () -> "");
    }

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    EvaluationSyncRunner syncRunner;

    @Test
    void omittedProviderAndAgentUseGrokWithoutEchoingTheKey() throws Exception {
        String token = register("grok-" + UUID.randomUUID() + "@example.com", "Owner");
        UUID orgId = createOrg(token, "Grok Org " + UUID.randomUUID(), "gk-" + UUID.randomUUID().toString().substring(0, 8));

        mockMvc.perform(post("/api/v1/orgs/{orgId}/agents", orgId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"implicit-grok","config":{}}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.adapterType").value("GROK"))
                .andExpect(jsonPath("$.config.apiKey").doesNotExist());

        Fixture ids = seedPublishedBenchmark(token, orgId);
        String patchJson = objectMapper.writeValueAsString(REFERENCE_PATCH);
        wireMock.stubFor(com.github.tomakehurst.wiremock.client.WireMock.post(urlEqualTo("/v1/chat/completions"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"id":"chatcmpl-grok","choices":[{"finish_reason":"stop","message":{"role":"assistant","content":%s}}],"usage":{"prompt_tokens":2,"completion_tokens":3,"total_tokens":5}}
                                """.formatted(patchJson))));

        MvcResult evalResult = mockMvc.perform(post("/api/v1/orgs/{orgId}/evaluations", orgId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"benchmarkVersionId":"%s","taskId":"%s"}
                                """.formatted(ids.versionId(), ids.taskId())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.agentId").exists())
                .andReturn();
        UUID evaluationId = UUID.fromString(objectMapper.readTree(evalResult.getResponse().getContentAsString()).get("id").asText());
        UUID agentId = UUID.fromString(objectMapper.readTree(evalResult.getResponse().getContentAsString()).get("agentId").asText());

        mockMvc.perform(patch("/api/v1/orgs/{orgId}/agents/{agentId}", orgId, agentId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"config":{"baseUrl":"%s","maxRetries":0,"timeoutSec":15}}
                                """.formatted(wireMock.baseUrl())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.adapterType").value("GROK"));

        mockMvc.perform(post("/api/v1/orgs/{orgId}/agents/{agentId}/test-connection", orgId, agentId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.provider").value("GROK"))
                .andExpect(jsonPath("$.hasCredentials").value(true))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(FIXTURE_KEY))));

        assertTrue(syncRunner.drainAndProcess(40) > 0);

        MvcResult detail = mockMvc.perform(get("/api/v1/orgs/{orgId}/evaluations/{id}", orgId, evaluationId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.evaluation.state").value("COMPLETED"))
                .andExpect(jsonPath("$.agentInvocations[0].provider").value("GROK"))
                .andExpect(jsonPath("$.agentInvocations[0].model").value("grok-test"))
                .andExpect(jsonPath("$.agentInvocations[0].status").value("SUCCEEDED"))
                .andReturn();
        assertFalse(detail.getResponse().getContentAsString().contains(FIXTURE_KEY));
    }

    @Test
    void fakeAgentStillRunsWhenNoModelKeyIsRequired() throws Exception {
        String token = register("fake-" + UUID.randomUUID() + "@example.com", "Owner");
        UUID orgId = createOrg(token, "Fake Org " + UUID.randomUUID(), "fk-" + UUID.randomUUID().toString().substring(0, 8));
        String patch = objectMapper.writeValueAsString(REFERENCE_PATCH);
        MvcResult agent = mockMvc.perform(post("/api/v1/orgs/{orgId}/agents", orgId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"local-fake","adapterType":"FAKE","config":{"patch":%s}}
                                """.formatted(patch)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.adapterType").value("FAKE"))
                .andReturn();
        UUID agentId = UUID.fromString(objectMapper.readTree(agent.getResponse().getContentAsString()).get("id").asText());
        Fixture ids = seedPublishedBenchmark(token, orgId);

        MvcResult evalResult = mockMvc.perform(post("/api/v1/orgs/{orgId}/evaluations", orgId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"benchmarkVersionId":"%s","taskId":"%s","agentId":"%s"}
                                """.formatted(ids.versionId(), ids.taskId(), agentId)))
                .andExpect(status().isCreated())
                .andReturn();
        UUID evaluationId = UUID.fromString(objectMapper.readTree(evalResult.getResponse().getContentAsString()).get("id").asText());
        assertTrue(syncRunner.drainAndProcess(40) > 0);
        mockMvc.perform(get("/api/v1/orgs/{orgId}/evaluations/{id}", orgId, evaluationId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.evaluation.state").value("COMPLETED"))
                .andExpect(jsonPath("$.agentInvocations[0].provider").value("FAKE"));
    }

    private Fixture seedPublishedBenchmark(String token, UUID orgId) throws Exception {
        MvcResult bench = mockMvc.perform(post("/api/v1/orgs/{orgId}/benchmarks", orgId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Grok Bench %s","description":"grok default"}
                                """.formatted(UUID.randomUUID().toString().substring(0, 8))))
                .andExpect(status().isCreated())
                .andReturn();
        UUID benchmarkId = UUID.fromString(objectMapper.readTree(bench.getResponse().getContentAsString()).get("id").asText());

        MvcResult version = mockMvc.perform(post("/api/v1/orgs/{orgId}/benchmarks/{benchmarkId}/versions", orgId, benchmarkId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"version":"1.0.0","changelog":"grok"}
                                """))
                .andExpect(status().isCreated())
                .andReturn();
        UUID versionId = UUID.fromString(objectMapper.readTree(version.getResponse().getContentAsString()).get("id").asText());

        String taskBody = """
                {
                  "slug":"null-safe",
                  "title":"Null-safe getter",
                  "prompt":"Make get() null-safe",
                  "referencePatch":%s,
                  "timeoutSec":600,
                  "memoryMb":512,
                  "maxPatchBytes":100000,
                  "evalConfig":{"requireTargetFailAtBaseline":true,"allowedPatchPaths":["src/"]}
                }
                """.formatted(objectMapper.writeValueAsString(REFERENCE_PATCH));
        MvcResult task = mockMvc.perform(post("/api/v1/orgs/{orgId}/benchmark-versions/{versionId}/tasks", orgId, versionId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(taskBody))
                .andExpect(status().isCreated())
                .andReturn();
        UUID taskId = UUID.fromString(objectMapper.readTree(task.getResponse().getContentAsString()).get("id").asText());

        String suiteBody = objectMapper.createObjectNode()
                .put("framework", "fixture-v1")
                .put("entryCommand", "fixture")
                .put("fixtureContent", FIXTURE)
                .put("caseCount", 1)
                .set("expectedOutcomes", objectMapper.createObjectNode())
                .toString();
        mockMvc.perform(put("/api/v1/orgs/{orgId}/tasks/{taskId}/suites/visible", orgId, taskId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(suiteBody))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/orgs/{orgId}/benchmark-versions/{versionId}/publish", orgId, versionId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        return new Fixture(versionId, taskId);
    }

    private String register(String email, String displayName) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"password123","displayName":"%s"}
                                """.formatted(email, displayName)))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("accessToken").asText();
    }

    private UUID createOrg(String token, String name, String slug) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/orgs")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"%s","slug":"%s"}
                                """.formatted(name, slug)))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText());
    }

    private record Fixture(UUID versionId, UUID taskId) {
    }
}
