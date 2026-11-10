package com.agentbench.evaluation;

import com.agentbench.evaluation.service.EvaluationSyncRunner;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Milestone 4: evaluation state machine, duplicate job protection, cancel path,
 * baseline + patch validation with fixture runner (Testcontainers Postgres).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class EvaluationOrchestrationTest {

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

    private static final String HIDDEN_FIXTURE = """
            {
              "cases": [
                {
                  "key": "hidden.generalizes",
                  "role": "hidden",
                  "postChecks": [
                    {"type": "file_contains", "path": "src/Bug.java", "needle": "Objects.requireNonNull", "expectPresent": true}
                  ]
                },
                {
                  "key": "edge.nullValue",
                  "role": "edge",
                  "postChecks": [
                    {"type": "file_contains", "path": "src/Bug.java", "needle": "requireNonNull", "expectPresent": true}
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

    @Container
    static PostgreSQLContainer<?> postgres = PostgresTestSupport.postgres();

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        PostgresTestSupport.registerRlsAwareDatasource(registry, postgres);
        registry.add("agentbench.evaluation.listener-enabled", () -> "false");
    }

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    EvaluationSyncRunner syncRunner;

    @Test
    void evaluationRunsThroughLifecycleWithBaselineAndPatch() throws Exception {
        String token = register("owner-" + UUID.randomUUID() + "@example.com", "Owner");
        UUID orgId = createOrg(token, "Eval Org " + UUID.randomUUID(), "ev-" + UUID.randomUUID().toString().substring(0, 8));
        Fixture ids = seedPublishedBenchmark(token, orgId);
        UUID agentId = createFakeAgent(token, orgId, REFERENCE_PATCH);

        MvcResult created = mockMvc.perform(post("/api/v1/orgs/{orgId}/evaluations", orgId)
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", "idem-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"benchmarkVersionId":"%s","taskId":"%s","agentId":"%s"}
                                """.formatted(ids.versionId(), ids.taskId(), agentId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.state").value("QUEUED"))
                .andReturn();
        UUID evaluationId = UUID.fromString(objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asText());

        // Duplicate idempotency returns same evaluation
        String idem = "same-key-" + UUID.randomUUID();
        MvcResult first = mockMvc.perform(post("/api/v1/orgs/{orgId}/evaluations", orgId)
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", idem)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"benchmarkVersionId":"%s","taskId":"%s","agentId":"%s"}
                                """.formatted(ids.versionId(), ids.taskId(), agentId)))
                .andExpect(status().isCreated())
                .andReturn();
        UUID firstId = UUID.fromString(objectMapper.readTree(first.getResponse().getContentAsString()).get("id").asText());
        MvcResult second = mockMvc.perform(post("/api/v1/orgs/{orgId}/evaluations", orgId)
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", idem)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"benchmarkVersionId":"%s","taskId":"%s","agentId":"%s"}
                                """.formatted(ids.versionId(), ids.taskId(), agentId)))
                .andExpect(status().isCreated())
                .andReturn();
        UUID secondId = UUID.fromString(objectMapper.readTree(second.getResponse().getContentAsString()).get("id").asText());
        assertEquals(firstId, secondId);

        int processed = syncRunner.drainAndProcess(40);
        assertTrue(processed > 0);

        MvcResult detail = mockMvc.perform(get("/api/v1/orgs/{orgId}/evaluations/{id}", orgId, evaluationId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.evaluation.state").value("COMPLETED"))
                .andExpect(jsonPath("$.patch.sealed").value(true))
                .andExpect(jsonPath("$.transitions").isArray())
                .andReturn();

        JsonNode body = objectMapper.readTree(detail.getResponse().getContentAsString());
        assertTrue(body.get("transitions").size() >= 10);
        boolean hasBaseline = false;
        boolean hasF2p = false;
        boolean hasHidden = false;
        boolean hiddenHasTransitionLabel = false;
        for (JsonNode run : body.get("testRuns")) {
            if ("BASELINE".equals(run.get("phase").asText())) {
                hasBaseline = true;
                assertEquals("SUCCEEDED", run.get("status").asText());
                assertTrue(run.get("results").size() >= 2);
            }
            if ("VISIBLE_POST".equals(run.get("phase").asText())) {
                for (JsonNode result : run.get("results")) {
                    if ("F2P".equals(result.path("transition").asText())) {
                        hasF2p = true;
                    }
                }
            }
            if ("HIDDEN_POST".equals(run.get("phase").asText())) {
                hasHidden = true;
                for (JsonNode result : run.get("results")) {
                    if (!result.path("transition").isNull() && !result.path("transition").asText("").isBlank()) {
                        hiddenHasTransitionLabel = true;
                    }
                }
            }
        }
        assertTrue(hasBaseline);
        assertTrue(hasF2p);
        assertTrue(hasHidden);
        assertTrue(!hiddenHasTransitionLabel, "Hidden suite must never be labeled F2P/P2P/P2F/F2F");

        JsonNode score = body.get("score");
        assertTrue(score != null && !score.isNull());
        assertEquals("VALID", score.get("scoreStatus").asText());
        assertTrue(score.get("weightedTotal").asDouble() > 0);
        assertTrue(score.get("metrics").isArray());
        assertTrue(score.get("metrics").size() >= 6);
        assertTrue(score.get("transitionSummary").get("F2P").asInt() >= 1);

        mockMvc.perform(get("/api/v1/orgs/{orgId}/evaluations/{id}/report", orgId, evaluationId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.score.scoreStatus").value("VALID"))
                .andExpect(jsonPath("$.hidden.neverLabeledF2P").value(true))
                .andExpect(jsonPath("$.hidden.total").value(2))
                .andExpect(jsonPath("$.visibleTransitions.F2P").isNumber());

        // Duplicate message protection: reprocessing same completed evaluation command is a no-op
        int again = syncRunner.drainAndProcess(5);
        assertEquals(0, again);
    }

    @Test
    void cancelQueuedEvaluation() throws Exception {
        String token = register("cancel-" + UUID.randomUUID() + "@example.com", "Cancel");
        UUID orgId = createOrg(token, "Cancel Org " + UUID.randomUUID(), "ca-" + UUID.randomUUID().toString().substring(0, 8));
        Fixture ids = seedPublishedBenchmark(token, orgId);
        UUID agentId = createFakeAgent(token, orgId, REFERENCE_PATCH);

        MvcResult created = mockMvc.perform(post("/api/v1/orgs/{orgId}/evaluations", orgId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"benchmarkVersionId":"%s","taskId":"%s","agentId":"%s"}
                                """.formatted(ids.versionId(), ids.taskId(), agentId)))
                .andExpect(status().isCreated())
                .andReturn();
        UUID evaluationId = UUID.fromString(objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asText());

        mockMvc.perform(post("/api/v1/orgs/{orgId}/evaluations/{id}/cancel", orgId, evaluationId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("CANCELLED"))
                .andExpect(jsonPath("$.failureClass").value("USER"))
                .andExpect(jsonPath("$.errorCode").value("CANCELLED_BY_USER"));

        mockMvc.perform(get("/api/v1/orgs/{orgId}/evaluations/{id}/transitions", orgId, evaluationId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].toState").value("QUEUED"))
                .andExpect(jsonPath("$[1].toState").value("CANCELLED"));
    }

    @Test
    void crossOrgCannotAccessEvaluations() throws Exception {
        String tokenA = register("a-" + UUID.randomUUID() + "@example.com", "A");
        String tokenB = register("b-" + UUID.randomUUID() + "@example.com", "B");
        UUID orgA = createOrg(tokenA, "OrgA " + UUID.randomUUID(), "oa-" + UUID.randomUUID().toString().substring(0, 8));
        createOrg(tokenB, "OrgB " + UUID.randomUUID(), "ob-" + UUID.randomUUID().toString().substring(0, 8));
        Fixture ids = seedPublishedBenchmark(tokenA, orgA);
        UUID agentId = createFakeAgent(tokenA, orgA, REFERENCE_PATCH);

        MvcResult created = mockMvc.perform(post("/api/v1/orgs/{orgId}/evaluations", orgA)
                        .header("Authorization", "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"benchmarkVersionId":"%s","taskId":"%s","agentId":"%s"}
                                """.formatted(ids.versionId(), ids.taskId(), agentId)))
                .andExpect(status().isCreated())
                .andReturn();
        UUID evaluationId = UUID.fromString(objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asText());

        mockMvc.perform(get("/api/v1/orgs/{orgId}/evaluations", orgA)
                        .header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/orgs/{orgId}/evaluations/{id}", orgA, evaluationId)
                        .header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isForbidden());
    }

    private Fixture seedPublishedBenchmark(String token, UUID orgId) throws Exception {
        UUID benchmarkId = createBenchmark(token, orgId);
        UUID versionId = createVersion(token, orgId, benchmarkId);
        UUID taskId = createTask(token, orgId, versionId);
        upsertVisible(token, orgId, taskId);
        upsertHidden(token, orgId, taskId);
        mockMvc.perform(post("/api/v1/orgs/{orgId}/benchmark-versions/{versionId}/publish", orgId, versionId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        return new Fixture(benchmarkId, versionId, taskId);
    }

    private UUID createFakeAgent(String token, UUID orgId, String patch) throws Exception {
        String escaped = objectMapper.writeValueAsString(patch);
        MvcResult result = mockMvc.perform(post("/api/v1/orgs/{orgId}/agents", orgId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Fake-%s","adapterType":"FAKE","config":{"patch":%s}}
                                """.formatted(UUID.randomUUID().toString().substring(0, 8), escaped)))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText());
    }

    private String register(String email, String name) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"password123","displayName":"%s"}
                                """.formatted(email, name)))
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

    private UUID createBenchmark(String token, UUID orgId) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/orgs/{orgId}/benchmarks", orgId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Eval Bench %s","description":"m4"}
                                """.formatted(UUID.randomUUID().toString().substring(0, 8))))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText());
    }

    private UUID createVersion(String token, UUID orgId, UUID benchmarkId) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/orgs/{orgId}/benchmarks/{benchmarkId}/versions", orgId, benchmarkId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"version":"1.0.0","changelog":"m4"}
                                """))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText());
    }

    private UUID createTask(String token, UUID orgId, UUID versionId) throws Exception {
        String body = objectMapper.createObjectNode()
                .put("slug", "null-safe")
                .put("title", "Null-safe getter")
                .put("prompt", "Make get() null-safe")
                .put("acceptanceCriteria", "requireNonNull")
                .put("referencePatch", REFERENCE_PATCH)
                .put("timeoutSec", 600)
                .put("memoryMb", 512)
                .put("maxPatchBytes", 100_000)
                .set("evalConfig", objectMapper.createObjectNode()
                        .put("requireTargetFailAtBaseline", true)
                        .set("allowedPatchPaths", objectMapper.createArrayNode().add("src/")))
                .toString();
        // Fix evalConfig embedding — rebuild properly
        body = """
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
        MvcResult result = mockMvc.perform(post("/api/v1/orgs/{orgId}/benchmark-versions/{versionId}/tasks", orgId, versionId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText());
    }

    private void upsertVisible(String token, UUID orgId, UUID taskId) throws Exception {
        String body = objectMapper.createObjectNode()
                .put("framework", "fixture-v1")
                .put("entryCommand", "fixture")
                .put("fixtureContent", FIXTURE)
                .put("caseCount", 2)
                .set("expectedOutcomes", objectMapper.createObjectNode())
                .toString();
        mockMvc.perform(put("/api/v1/orgs/{orgId}/tasks/{taskId}/suites/visible", orgId, taskId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());
    }

    private void upsertHidden(String token, UUID orgId, UUID taskId) throws Exception {
        String body = objectMapper.createObjectNode()
                .put("framework", "fixture-v1")
                .put("entryCommand", "fixture")
                .put("fixtureContent", HIDDEN_FIXTURE)
                .put("caseCount", 2)
                .set("expectedOutcomes", objectMapper.createObjectNode())
                .toString();
        mockMvc.perform(put("/api/v1/orgs/{orgId}/tasks/{taskId}/suites/hidden", orgId, taskId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());
    }

    private record Fixture(UUID benchmarkId, UUID versionId, UUID taskId) {
    }
}
