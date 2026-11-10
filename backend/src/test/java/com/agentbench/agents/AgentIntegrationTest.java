package com.agentbench.agents;

import com.agentbench.evaluation.service.EvaluationSyncRunner;
import com.fasterxml.jackson.databind.JsonNode;
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
import com.agentbench.support.PostgresTestSupport;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Milestone 5: agent CRUD/RBAC, OpenAI-compatible provider via WireMock, invocation audit on evaluations.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class AgentIntegrationTest {

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
                },
                {
                  "key": "control.stillCompiles",
                  "role": "control",
                  "baselineChecks": [
                    {"type": "file_contains", "path": "src/Bug.java", "needle": "class Bug", "expectPresent": true}
                  ],
                  "postChecks": [
                    {"type": "file_contains", "path": "src/Bug.java", "needle": "class Bug", "expectPresent": true}
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
    }

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    EvaluationSyncRunner syncRunner;

    @Test
    void agentCrudRbacAndOpenAiCompatibleEvaluation() throws Exception {
        String ownerToken = register("owner-" + UUID.randomUUID() + "@example.com", "Owner");
        UUID orgId = createOrg(ownerToken, "Agent Org " + UUID.randomUUID(), "ag-" + UUID.randomUUID().toString().substring(0, 8));

        mockMvc.perform(post("/api/v1/orgs/{orgId}/agents", orgId)
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"bad","adapterType":"OPENAI_COMPATIBLE","config":{"apiKey":"sk-leak"}}
                                """))
                .andExpect(status().isBadRequest());

        MvcResult secretResult = mockMvc.perform(post("/api/v1/orgs/{orgId}/secrets", orgId)
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"openai-key","purpose":"AGENT","plaintext":"sk-wiremock-test-key"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.plaintext").doesNotExist())
                .andReturn();
        UUID secretId = UUID.fromString(objectMapper.readTree(secretResult.getResponse().getContentAsString()).get("id").asText());

        String patchJson = objectMapper.writeValueAsString("```diff\n" + REFERENCE_PATCH + "\n```");
        wireMock.stubFor(com.github.tomakehurst.wiremock.client.WireMock.post(urlEqualTo("/v1/chat/completions"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                  "id":"chatcmpl-m5",
                                  "choices":[{"finish_reason":"stop","message":{"role":"assistant","content":%s}}],
                                  "usage":{"prompt_tokens":5,"completion_tokens":7,"total_tokens":12}
                                }
                                """.formatted(patchJson))));

        MvcResult agentResult = mockMvc.perform(post("/api/v1/orgs/{orgId}/agents", orgId)
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name":"openai-fixer",
                                  "adapterType":"OPENAI_COMPATIBLE",
                                  "secretRefId":"%s",
                                  "config":{
                                    "model":"gpt-test",
                                    "baseUrl":"%s",
                                    "maxRetries":1,
                                    "timeoutSec":15,
                                    "costPer1kPromptTokens":0.01,
                                    "costPer1kCompletionTokens":0.03
                                  }
                                }
                                """.formatted(secretId, wireMock.baseUrl())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.adapterType").value("OPENAI_COMPATIBLE"))
                .andExpect(jsonPath("$.secretRefId").value(secretId.toString()))
                .andExpect(jsonPath("$.config.apiKey").doesNotExist())
                .andReturn();
        UUID agentId = UUID.fromString(objectMapper.readTree(agentResult.getResponse().getContentAsString()).get("id").asText());

        mockMvc.perform(post("/api/v1/orgs/{orgId}/agents/{agentId}/test-connection", orgId, agentId)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.hasCredentials").value(true));

        mockMvc.perform(patch("/api/v1/orgs/{orgId}/agents/{agentId}", orgId, agentId)
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"config":{"model":"gpt-test","baseUrl":"%s","maxRetries":1,"timeoutSec":15,"costPer1kPromptTokens":0.01,"costPer1kCompletionTokens":0.03}}
                                """.formatted(wireMock.baseUrl())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.config.model").value("gpt-test"));

        Fixture ids = seedPublishedBenchmark(ownerToken, orgId);

        MvcResult evalResult = mockMvc.perform(post("/api/v1/orgs/{orgId}/evaluations", orgId)
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"benchmarkVersionId":"%s","taskId":"%s","agentId":"%s"}
                                """.formatted(ids.versionId(), ids.taskId(), agentId)))
                .andExpect(status().isCreated())
                .andReturn();
        UUID evaluationId = UUID.fromString(objectMapper.readTree(evalResult.getResponse().getContentAsString()).get("id").asText());

        int processed = syncRunner.drainAndProcess(40);
        assertTrue(processed > 0);

        MvcResult detail = mockMvc.perform(get("/api/v1/orgs/{orgId}/evaluations/{id}", orgId, evaluationId)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.evaluation.state").value("COMPLETED"))
                .andExpect(jsonPath("$.agentInvocations").isArray())
                .andExpect(jsonPath("$.agentInvocations[0].provider").value("OPENAI_COMPATIBLE"))
                .andExpect(jsonPath("$.agentInvocations[0].status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.agentInvocations[0].promptTokens").value(5))
                .andExpect(jsonPath("$.agentInvocations[0].totalTokens").value(12))
                .andExpect(jsonPath("$.patch.sealed").value(true))
                .andReturn();

        String detailBody = detail.getResponse().getContentAsString();
        assertTrue(!detailBody.contains("sk-wiremock-test-key"));
        JsonNode invocations = objectMapper.readTree(detailBody).path("agentInvocations");
        assertTrue(invocations.size() >= 1);
        assertEquals("gpt-test", invocations.get(0).path("model").asText());
    }

    @Test
    void viewerCannotCreateAgent() throws Exception {
        String ownerToken = register("owner2-" + UUID.randomUUID() + "@example.com", "Owner");
        UUID orgId = createOrg(ownerToken, "RBAC Org " + UUID.randomUUID(), "rb-" + UUID.randomUUID().toString().substring(0, 8));

        String viewerEmail = "viewer-" + UUID.randomUUID() + "@example.com";
        String viewerToken = register(viewerEmail, "Viewer");

        mockMvc.perform(post("/api/v1/orgs/{orgId}/members", orgId)
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","role":"VIEWER"}
                                """.formatted(viewerEmail)))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/orgs/{orgId}/agents", orgId)
                        .header("Authorization", "Bearer " + viewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"nope","adapterType":"FAKE","config":{}}
                                """))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/v1/orgs/{orgId}/agents", orgId)
                        .header("Authorization", "Bearer " + viewerToken))
                .andExpect(status().isOk());
    }

    private Fixture seedPublishedBenchmark(String token, UUID orgId) throws Exception {
        MvcResult bench = mockMvc.perform(post("/api/v1/orgs/{orgId}/benchmarks", orgId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"M5 Bench %s","description":"agent integration"}
                                """.formatted(UUID.randomUUID().toString().substring(0, 8))))
                .andExpect(status().isCreated())
                .andReturn();
        UUID benchmarkId = UUID.fromString(objectMapper.readTree(bench.getResponse().getContentAsString()).get("id").asText());

        MvcResult version = mockMvc.perform(post("/api/v1/orgs/{orgId}/benchmarks/{benchmarkId}/versions", orgId, benchmarkId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"version":"1.0.0","changelog":"m5"}
                                """))
                .andExpect(status().isCreated())
                .andReturn();
        UUID versionId = UUID.fromString(objectMapper.readTree(version.getResponse().getContentAsString()).get("id").asText());

        String taskBody = """
                {
                  "slug":"null-safe",
                  "title":"Null-safe getter",
                  "prompt":"Make get() null-safe",
                  "acceptanceCriteria":"requireNonNull",
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
                .put("caseCount", 2)
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
