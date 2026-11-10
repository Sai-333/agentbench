package com.agentbench.benchmarks;

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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Milestone 3: benchmark CRUD, publish immutability, hidden-suite confidentiality,
 * and agent-facing context allowlist.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class BenchmarkManagementTest {

    private static final String HIDDEN_FIXTURE = "assertEquals(42, secretHiddenOracle());";

    @Container
    static PostgreSQLContainer<?> postgres = PostgresTestSupport.postgres();

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        PostgresTestSupport.registerRlsAwareDatasource(registry, postgres);
    }

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Test
    void publishFreezesVersionAndBlocksEdits() throws Exception {
        String ownerToken = register("owner-" + UUID.randomUUID() + "@example.com", "Owner");
        UUID orgId = createOrg(ownerToken, "Bench Org " + UUID.randomUUID(), "bench-" + UUID.randomUUID().toString().substring(0, 8));
        UUID projectId = createProject(ownerToken, orgId, "Demo Project");

        UUID benchmarkId = createBenchmark(ownerToken, orgId, projectId, "Fix Suite");
        UUID versionId = createVersion(ownerToken, orgId, benchmarkId, "1.0.0");
        UUID taskId = createTask(ownerToken, orgId, versionId, "null-deref");

        upsertVisible(ownerToken, orgId, taskId);
        upsertHidden(ownerToken, orgId, taskId);

        mockMvc.perform(post("/api/v1/orgs/{orgId}/benchmark-versions/{versionId}/publish", orgId, versionId)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PUBLISHED"))
                .andExpect(jsonPath("$.immutableSnapshot.schema").value("agentbench.benchmark-version.v1"))
                .andExpect(jsonPath("$.immutableSnapshot.tasks[0].slug").value("null-deref"));

        mockMvc.perform(put("/api/v1/orgs/{orgId}/tasks/{taskId}", orgId, taskId)
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"Should fail","prompt":"nope","acceptanceCriteria":""}
                                """))
                .andExpect(status().isConflict());

        mockMvc.perform(put("/api/v1/orgs/{orgId}/tasks/{taskId}/suites/visible", orgId, taskId)
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"framework":"junit","entryCommand":"mvn test","fixtureContent":"x"}
                                """))
                .andExpect(status().isConflict());
    }

    @Test
    void viewerCannotReadHiddenFixturesAndAgentContextOmitsThem() throws Exception {
        String ownerToken = register("author-" + UUID.randomUUID() + "@example.com", "Author");
        String viewerToken = register("viewer-" + UUID.randomUUID() + "@example.com", "Viewer");
        UUID orgId = createOrg(ownerToken, "Hidden Org " + UUID.randomUUID(), "hid-" + UUID.randomUUID().toString().substring(0, 8));

        mockMvc.perform(post("/api/v1/orgs/{orgId}/members", orgId)
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","role":"VIEWER"}
                                """.formatted(meEmail(viewerToken))))
                .andExpect(status().isCreated());

        UUID benchmarkId = createBenchmark(ownerToken, orgId, null, "Confidential");
        UUID versionId = createVersion(ownerToken, orgId, benchmarkId, "0.1.0");
        UUID taskId = createTask(ownerToken, orgId, versionId, "leak-check");
        upsertVisible(ownerToken, orgId, taskId);
        upsertHidden(ownerToken, orgId, taskId);

        MvcResult hiddenAsViewer = mockMvc.perform(get("/api/v1/orgs/{orgId}/tasks/{taskId}/suites/hidden", orgId, taskId)
                        .header("Authorization", "Bearer " + viewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.visibility").value("HIDDEN"))
                .andExpect(jsonPath("$.contentRestricted").value(true))
                .andExpect(jsonPath("$.fixtureContent").doesNotExist())
                .andExpect(jsonPath("$.storageUri").doesNotExist())
                .andExpect(jsonPath("$.entryCommand").doesNotExist())
                .andExpect(jsonPath("$.expectedOutcomes").doesNotExist())
                .andReturn();
        String viewerBody = hiddenAsViewer.getResponse().getContentAsString();
        assertFalse(viewerBody.contains(HIDDEN_FIXTURE), "Viewer response leaked hidden fixture");
        assertFalse(viewerBody.contains("secretHiddenOracle"), "Viewer response leaked hidden oracle");

        MvcResult taskAsViewer = mockMvc.perform(get("/api/v1/orgs/{orgId}/tasks/{taskId}", orgId, taskId)
                        .header("Authorization", "Bearer " + viewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hasHiddenSuite").value(true))
                .andExpect(jsonPath("$.hiddenSuite.contentRestricted").value(true))
                .andExpect(jsonPath("$.hiddenSuite.fixtureContent").doesNotExist())
                .andReturn();
        assertFalse(taskAsViewer.getResponse().getContentAsString().contains(HIDDEN_FIXTURE));

        MvcResult agentCtx = mockMvc.perform(get("/api/v1/orgs/{orgId}/tasks/{taskId}/agent-context", orgId, taskId)
                        .header("Authorization", "Bearer " + viewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.prompt").value("Fix the null dereference"))
                .andExpect(jsonPath("$.visibleSuite.framework").value("junit"))
                .andExpect(jsonPath("$.hiddenSuite").doesNotExist())
                .andExpect(jsonPath("$.referencePatch").doesNotExist())
                .andExpect(jsonPath("$.acceptanceCriteria").doesNotExist())
                .andExpect(jsonPath("$.expectedBehavior").doesNotExist())
                .andReturn();
        String agentBody = agentCtx.getResponse().getContentAsString();
        assertFalse(agentBody.contains(HIDDEN_FIXTURE));
        assertFalse(agentBody.contains("HIDDEN"));
        assertFalse(agentBody.contains("secretHiddenOracle"));
        assertFalse(agentBody.contains("reference patch"));

        // Author/owner can still read hidden content
        mockMvc.perform(get("/api/v1/orgs/{orgId}/tasks/{taskId}/suites/hidden", orgId, taskId)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.contentRestricted").value(false))
                .andExpect(jsonPath("$.fixtureContent").value(HIDDEN_FIXTURE));
    }

    @Test
    void crossOrgCannotAccessBenchmarks() throws Exception {
        String tokenA = register("a-" + UUID.randomUUID() + "@example.com", "A");
        String tokenB = register("b-" + UUID.randomUUID() + "@example.com", "B");
        UUID orgA = createOrg(tokenA, "OrgA " + UUID.randomUUID(), "oa-" + UUID.randomUUID().toString().substring(0, 8));
        createOrg(tokenB, "OrgB " + UUID.randomUUID(), "ob-" + UUID.randomUUID().toString().substring(0, 8));
        UUID benchmarkId = createBenchmark(tokenA, orgA, null, "Private Bench");

        mockMvc.perform(get("/api/v1/orgs/{orgId}/benchmarks", orgA)
                        .header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/v1/orgs/{orgId}/benchmarks/{benchmarkId}", orgA, benchmarkId)
                        .header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isForbidden());
    }

    @Test
    void viewerCannotCreateBenchmark() throws Exception {
        String ownerToken = register("own-" + UUID.randomUUID() + "@example.com", "Own");
        String viewerToken = register("view-" + UUID.randomUUID() + "@example.com", "View");
        UUID orgId = createOrg(ownerToken, "RBAC " + UUID.randomUUID(), "rb-" + UUID.randomUUID().toString().substring(0, 8));
        mockMvc.perform(post("/api/v1/orgs/{orgId}/members", orgId)
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","role":"VIEWER"}
                                """.formatted(meEmail(viewerToken))))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/orgs/{orgId}/benchmarks", orgId)
                        .header("Authorization", "Bearer " + viewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Nope","description":""}
                                """))
                .andExpect(status().isForbidden());
    }

    private void upsertVisible(String token, UUID orgId, UUID taskId) throws Exception {
        mockMvc.perform(put("/api/v1/orgs/{orgId}/tasks/{taskId}/suites/visible", orgId, taskId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "framework":"junit",
                                  "entryCommand":"mvn -q test",
                                  "fixtureContent":"@Test void visible() {}",
                                  "caseCount":1,
                                  "expectedOutcomes":{"visible":"FAIL_AT_BASELINE"}
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.visibility").value("VISIBLE"));
    }

    private void upsertHidden(String token, UUID orgId, UUID taskId) throws Exception {
        mockMvc.perform(put("/api/v1/orgs/{orgId}/tasks/{taskId}/suites/hidden", orgId, taskId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "framework":"junit",
                                  "entryCommand":"mvn -q -Dtest=HiddenIT test",
                                  "fixtureContent":"%s",
                                  "caseCount":1,
                                  "expectedOutcomes":{"hidden":"PASS"}
                                }
                                """.formatted(HIDDEN_FIXTURE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.visibility").value("HIDDEN"));
    }

    private UUID createBenchmark(String token, UUID orgId, UUID projectId, String name) throws Exception {
        String body = projectId == null
                ? "{\"name\":\"%s\",\"description\":\"coding challenge\"}".formatted(name)
                : "{\"name\":\"%s\",\"description\":\"coding challenge\",\"projectId\":\"%s\"}".formatted(name, projectId);
        MvcResult result = mockMvc.perform(post("/api/v1/orgs/{orgId}/benchmarks", orgId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value(name))
                .andReturn();
        return UUID.fromString(objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText());
    }

    private UUID createVersion(String token, UUID orgId, UUID benchmarkId, String version) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/orgs/{orgId}/benchmarks/{benchmarkId}/versions", orgId, benchmarkId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"version":"%s","changelog":"initial"}
                                """.formatted(version)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andReturn();
        return UUID.fromString(objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText());
    }

    private UUID createTask(String token, UUID orgId, UUID versionId, String slug) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/orgs/{orgId}/benchmark-versions/{versionId}/tasks", orgId, versionId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "slug":"%s",
                                  "title":"Null dereference",
                                  "prompt":"Fix the null dereference",
                                  "acceptanceCriteria":"Visible tests pass; no regressions",
                                  "category":"bugfix",
                                  "difficulty":"MEDIUM",
                                  "languageTags":["java"],
                                  "baselineCommitSha":"0123456789abcdef0123456789abcdef01234567",
                                  "referencePatch":"diff --git a/x b/x\\nreference patch",
                                  "expectedBehavior":"Method returns empty list instead of NPE",
                                  "timeoutSec":900,
                                  "memoryMb":1024,
                                  "evalConfig":{"toolchain":"maven"}
                                }
                                """.formatted(slug)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.slug").value(slug))
                .andReturn();
        return UUID.fromString(objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText());
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

    private UUID createProject(String token, UUID orgId, String name) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/orgs/{orgId}/projects", orgId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"%s","description":"demo"}
                                """.formatted(name)))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText());
    }

    private String meEmail(String token) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/me")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode node = objectMapper.readTree(result.getResponse().getContentAsString());
        return node.get("email").asText();
    }
}
