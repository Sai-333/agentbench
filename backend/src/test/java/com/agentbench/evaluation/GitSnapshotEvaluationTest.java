package com.agentbench.evaluation;

import com.agentbench.evaluation.service.EvaluationSyncRunner;
import com.agentbench.execution.service.GitSnapshotCheckout;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A published version pins a github.com commit, and the evaluation worker checks that
 * commit out. The tree is not fixture text, so the 500,000-character fixture cap stays.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class GitSnapshotEvaluationTest {

    private static final String CLICKIT_URL = "https://github.com/Sai-333/clickit-backend";
    private static final String CLICKIT_SHA = "754cfa442c192fbd3b482021a925deacdc7e3258";
    private static final String PUBLIC_URL = "https://github.com/Sai-333/agentbench";
    private static final String PUBLIC_SHA = "bcfa499d55f147c48fb3f4658baaa35135258d20";

    private static final String SUITE = """
            {
              "cases": [
                {
                  "key": "tree.pom",
                  "role": "control",
                  "baselineChecks": [
                    {"type": "file_contains", "path": "backend/pom.xml", "needle": "com.agentbench", "expectPresent": true}
                  ],
                  "postChecks": [
                    {"type": "file_contains", "path": "backend/pom.xml", "needle": "com.agentbench", "expectPresent": true}
                  ]
                }
              ]
            }
            """;

    private static final String PATCH = """
            diff --git a/notes/checkout.txt b/notes/checkout.txt
            new file mode 100644
            --- /dev/null
            +++ b/notes/checkout.txt
            @@ -0,0 +1 @@
            +checked out by the worker
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
    void fixtureTextOverTheCapIsRejected() throws Exception {
        String token = register("cap-" + UUID.randomUUID() + "@example.com", "Cap");
        UUID orgId = createOrg(token, "Cap " + UUID.randomUUID(), "cap-" + UUID.randomUUID().toString().substring(0, 8));
        UUID versionId = draft(token, orgId);
        UUID taskId = createTask(token, orgId, versionId, "small-suite", null, null);
        ObjectNode body = objectMapper.createObjectNode();
        body.put("framework", "fixture-v1");
        body.put("entryCommand", "fixture");
        body.put("fixtureContent", "x".repeat(500_001));
        body.put("caseCount", 1);
        mockMvc.perform(put("/api/v1/orgs/{orgId}/tasks/{taskId}/suites/visible", orgId, taskId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void nonGithubUrlIsRejectedAndClickitSnapshotIsPinned() throws Exception {
        String token = register("pin-" + UUID.randomUUID() + "@example.com", "Pin");
        UUID orgId = createOrg(token, "Pin " + UUID.randomUUID(), "pin-" + UUID.randomUUID().toString().substring(0, 8));
        UUID versionId = draft(token, orgId);

        mockMvc.perform(post("/api/v1/orgs/{orgId}/benchmark-versions/{versionId}/tasks", orgId, versionId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(taskJson("bad-host", "https://gitlab.com/acme/app", CLICKIT_SHA)))
                .andExpect(status().isBadRequest());

        UUID taskId = createTask(token, orgId, versionId, "clickit-backend", CLICKIT_URL, CLICKIT_SHA);
        saveSuite(token, orgId, taskId);

        mockMvc.perform(post("/api/v1/orgs/{orgId}/benchmark-versions/{versionId}/publish", orgId, versionId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.immutableSnapshot.tasks[0].gitUrl")
                        .value("https://github.com/Sai-333/clickit-backend.git"))
                .andExpect(jsonPath("$.immutableSnapshot.tasks[0].baselineCommitSha").value(CLICKIT_SHA))
                .andExpect(jsonPath("$.immutableSnapshot.tasks[0].repoSnapshot.gitUrl")
                        .value("https://github.com/Sai-333/clickit-backend.git"))
                .andExpect(jsonPath("$.immutableSnapshot.tasks[0].repoSnapshot.commitSha").value(CLICKIT_SHA))
                .andExpect(jsonPath("$.immutableSnapshot.tasks[0].repoSnapshot.contentDigest").value(CLICKIT_SHA))
                .andExpect(jsonPath("$.immutableSnapshot.tasks[0].suites[0].fixtureContent").value(SUITE.trim()));
    }

    @Test
    void existingRepoSnapshotIsPinnedOntoThePublishedVersion() throws Exception {
        String token = register("snap-" + UUID.randomUUID() + "@example.com", "Snap");
        UUID orgId = createOrg(token, "Snap " + UUID.randomUUID(), "sn-" + UUID.randomUUID().toString().substring(0, 8));
        MvcResult project = mockMvc.perform(post("/api/v1/orgs/{orgId}/projects", orgId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"ClickIt %s","description":"repo"}
                                """.formatted(UUID.randomUUID().toString().substring(0, 8))))
                .andExpect(status().isCreated())
                .andReturn();
        UUID projectId = UUID.fromString(objectMapper.readTree(project.getResponse().getContentAsString()).get("id").asText());
        MvcResult snapshot = mockMvc.perform(post("/api/v1/orgs/{orgId}/projects/{projectId}/snapshots", orgId, projectId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "gitUrl":"%s",
                                  "commitSha":"%s",
                                  "branch":"main",
                                  "contentDigest":"pinned-digest",
                                  "storageUri":"file:///tmp/operator-preload"
                                }
                                """.formatted(CLICKIT_URL, CLICKIT_SHA)))
                .andExpect(status().isCreated())
                .andReturn();
        String snapshotId = objectMapper.readTree(snapshot.getResponse().getContentAsString()).get("id").asText();

        UUID versionId = draft(token, orgId);
        ObjectNode task = (ObjectNode) objectMapper.readTree(taskJson("from-snapshot", null, null));
        task.put("repoSnapshotId", snapshotId);
        MvcResult created = mockMvc.perform(post("/api/v1/orgs/{orgId}/benchmark-versions/{versionId}/tasks", orgId, versionId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(task)))
                .andExpect(status().isCreated())
                .andReturn();
        UUID taskId = UUID.fromString(objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asText());
        saveSuite(token, orgId, taskId);

        mockMvc.perform(post("/api/v1/orgs/{orgId}/benchmark-versions/{versionId}/publish", orgId, versionId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.immutableSnapshot.tasks[0].repoSnapshot.gitUrl")
                        .value("https://github.com/Sai-333/clickit-backend.git"))
                .andExpect(jsonPath("$.immutableSnapshot.tasks[0].repoSnapshot.commitSha").value(CLICKIT_SHA))
                .andExpect(jsonPath("$.immutableSnapshot.tasks[0].repoSnapshot.branch").value("main"))
                .andExpect(jsonPath("$.immutableSnapshot.tasks[0].repoSnapshot.contentDigest").value("pinned-digest"))
                .andExpect(jsonPath("$.immutableSnapshot.tasks[0].repoSnapshot.storageUri")
                        .value("file:///tmp/operator-preload"));
    }

    @Test
    @Timeout(value = 8, unit = TimeUnit.MINUTES)
    void workerChecksOutThePinnedCommitPastTheFixtureCap() throws Exception {
        String token = register("run-" + UUID.randomUUID() + "@example.com", "Run");
        UUID orgId = createOrg(token, "Run " + UUID.randomUUID(), "run-" + UUID.randomUUID().toString().substring(0, 8));
        UUID versionId = draft(token, orgId);
        UUID taskId = createTask(token, orgId, versionId, "public-tree", PUBLIC_URL, PUBLIC_SHA);
        saveSuite(token, orgId, taskId);
        mockMvc.perform(post("/api/v1/orgs/{orgId}/benchmark-versions/{versionId}/publish", orgId, versionId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.immutableSnapshot.tasks[0].repoSnapshot.commitSha").value(PUBLIC_SHA));

        UUID agentId = createFakeAgent(token, orgId);
        UUID first = enqueue(token, orgId, versionId, taskId, agentId);
        UUID second = enqueue(token, orgId, versionId, taskId, agentId);

        Path firstWorkspace = workspace(orgId, first);
        Path secondWorkspace = workspace(orgId, second);
        Files.createDirectories(firstWorkspace);
        Files.writeString(firstWorkspace.resolve("OPERATOR-PRELOAD.txt"), "preloaded archive\n");
        assertFalse(Files.exists(secondWorkspace), "later run must not start from a preloaded directory");

        int processed = syncRunner.drainAndProcess(40);
        assertTrue(processed > 0);

        assertCheckedOut(firstWorkspace);
        assertCheckedOut(secondWorkspace);
        assertFalse(Files.exists(firstWorkspace.resolve("OPERATOR-PRELOAD.txt")));
        assertTrue(GitSnapshotCheckout.treeCharacterCount(firstWorkspace) > 500_000);
        assertTrue(GitSnapshotCheckout.treeCharacterCount(secondWorkspace) > 500_000);
        assertSuitePassed(token, orgId, first);
        assertSuitePassed(token, orgId, second);
    }

    private void assertCheckedOut(Path workspace) throws Exception {
        assertEquals(PUBLIC_SHA, gitHead(workspace));
        assertTrue(Files.exists(workspace.resolve("backend/pom.xml")));
    }

    private void assertSuitePassed(String token, UUID orgId, UUID evaluationId) throws Exception {
        MvcResult detail = mockMvc.perform(get("/api/v1/orgs/{orgId}/evaluations/{id}", orgId, evaluationId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.evaluation.state").value("COMPLETED"))
                .andReturn();
        JsonNode body = objectMapper.readTree(detail.getResponse().getContentAsString());
        boolean baselinePassed = false;
        for (JsonNode run : body.get("testRuns")) {
            if (!"BASELINE".equals(run.get("phase").asText())) {
                continue;
            }
            assertEquals("SUCCEEDED", run.get("status").asText());
            for (JsonNode result : run.get("results")) {
                if ("tree.pom".equals(result.get("caseKey").asText())) {
                    assertEquals("PASS", result.get("status").asText());
                    baselinePassed = true;
                }
            }
        }
        assertTrue(baselinePassed);
    }

    private static Path workspace(UUID orgId, UUID evaluationId) {
        return Path.of(System.getProperty("java.io.tmpdir"), "agentbench-it", "workspaces",
                orgId.toString(), evaluationId.toString());
    }

    private static String gitHead(Path workspace) throws Exception {
        Process process = new ProcessBuilder("git", "-C", workspace.toString(), "rev-parse", "HEAD")
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes()).trim();
        assertEquals(0, process.waitFor());
        return output;
    }

    private UUID enqueue(String token, UUID orgId, UUID versionId, UUID taskId, UUID agentId) throws Exception {
        MvcResult created = mockMvc.perform(post("/api/v1/orgs/{orgId}/evaluations", orgId)
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", "idem-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"benchmarkVersionId":"%s","taskId":"%s","agentId":"%s"}
                                """.formatted(versionId, taskId, agentId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.state").value("QUEUED"))
                .andReturn();
        return UUID.fromString(objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asText());
    }

    private UUID draft(String token, UUID orgId) throws Exception {
        MvcResult benchmark = mockMvc.perform(post("/api/v1/orgs/{orgId}/benchmarks", orgId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Snap %s","description":"git snapshot"}
                                """.formatted(UUID.randomUUID().toString().substring(0, 8))))
                .andExpect(status().isCreated())
                .andReturn();
        UUID benchmarkId = UUID.fromString(objectMapper.readTree(benchmark.getResponse().getContentAsString()).get("id").asText());
        MvcResult version = mockMvc.perform(post("/api/v1/orgs/{orgId}/benchmarks/{benchmarkId}/versions", orgId, benchmarkId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"version":"1.0.0","changelog":"pin"}
                                """))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(objectMapper.readTree(version.getResponse().getContentAsString()).get("id").asText());
    }

    private UUID createTask(String token, UUID orgId, UUID versionId, String slug, String gitUrl, String sha) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/orgs/{orgId}/benchmark-versions/{versionId}/tasks", orgId, versionId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(taskJson(slug, gitUrl, sha)))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText());
    }

    private String taskJson(String slug, String gitUrl, String sha) throws Exception {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("slug", slug);
        body.put("title", "Run the pinned tree");
        body.put("prompt", "The suite runs on the checked-out commit");
        body.put("acceptanceCriteria", "pom is present");
        if (gitUrl != null) {
            body.put("gitUrl", gitUrl);
        }
        if (sha != null) {
            body.put("baselineCommitSha", sha);
        }
        body.put("timeoutSec", 600);
        body.put("memoryMb", 512);
        ObjectNode eval = body.putObject("evalConfig");
        eval.put("requireTargetFailAtBaseline", false);
        return objectMapper.writeValueAsString(body);
    }

    private void saveSuite(String token, UUID orgId, UUID taskId) throws Exception {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("framework", "fixture-v1");
        body.put("entryCommand", "fixture");
        body.put("fixtureContent", SUITE.trim());
        body.put("caseCount", 1);
        assertTrue(SUITE.length() < 500_000);
        mockMvc.perform(put("/api/v1/orgs/{orgId}/tasks/{taskId}/suites/visible", orgId, taskId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk());
    }

    private UUID createFakeAgent(String token, UUID orgId) throws Exception {
        ObjectNode config = objectMapper.createObjectNode();
        config.put("patch", PATCH);
        ObjectNode body = objectMapper.createObjectNode();
        body.put("name", "Fake-" + UUID.randomUUID().toString().substring(0, 8));
        body.put("adapterType", "FAKE");
        body.set("config", config);
        MvcResult result = mockMvc.perform(post("/api/v1/orgs/{orgId}/agents", orgId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
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
}
