package com.agentbench.platform.regression;

import com.agentbench.support.PostgresTestSupport;
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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Ingest stores counts from case rows. Platform reads require an authenticated
 * OWNER or ADMIN of at least one organization.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class RegressionRunIngestIntegrationTest {

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
    void anonymousCannotIngestOrList() throws Exception {
        mockMvc.perform(get("/api/v1/regression-runs"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/regression-runs")
                        .contentType(MediaType.APPLICATION_XML)
                        .content("<testsuites></testsuites>"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void viewerDeveloperAndUserWithoutOrgAreForbidden() throws Exception {
        String owner = register("owner-" + UUID.randomUUID() + "@example.com", "Owner");
        String viewer = register("viewer-" + UUID.randomUUID() + "@example.com", "Viewer");
        String developer = register("dev-" + UUID.randomUUID() + "@example.com", "Dev");
        String stranger = register("stranger-" + UUID.randomUUID() + "@example.com", "Stranger");
        UUID orgId = createOrg(owner, "RBAC " + UUID.randomUUID(), "rbac-" + UUID.randomUUID().toString().substring(0, 8));
        addMember(owner, orgId, meEmail(viewer), "VIEWER");
        addMember(owner, orgId, meEmail(developer), "DEVELOPER");

        String xml = "<testsuite><testcase classname=\"t\" name=\"ok\" time=\"0.1\"/></testsuite>";
        for (String token : new String[] {viewer, developer, stranger}) {
            mockMvc.perform(get("/api/v1/regression-runs")
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isForbidden());
            mockMvc.perform(post("/api/v1/regression-runs")
                            .param("gitRepo", "https://github.com/Sai-333/agentbench")
                            .param("gitBranch", "main")
                            .param("gitSha", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
                            .param("suiteName", "selenium")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_XML)
                            .content(xml))
                    .andExpect(status().isForbidden());
        }
    }

    @Test
    void ownerIngestsJunitXmlAndReadsEveryCase() throws Exception {
        String owner = register("owner-" + UUID.randomUUID() + "@example.com", "Owner");
        createOrg(owner, "Runs " + UUID.randomUUID(), "runs-" + UUID.randomUUID().toString().substring(0, 8));
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <testsuites>
                  <testsuite name="pytest" failures="1" errors="1" skipped="1" tests="4" time="2.0"
                             timestamp="2026-11-11T04:00:00">
                    <testcase classname="tests.test_regression" name="test_ok" time="1.25"/>
                    <testcase classname="tests.test_regression" name="test_bad" time="0.5">
                      <failure message="AssertionError: boom">trace</failure>
                    </testcase>
                    <testcase classname="tests.test_regression" name="test_err" time="0">
                      <error message="exploded"/>
                    </testcase>
                    <testcase classname="tests.test_regression" name="test_skip" time="0">
                      <skipped message="later"/>
                    </testcase>
                  </testsuite>
                </testsuites>
                """;

        MvcResult created = mockMvc.perform(post("/api/v1/regression-runs")
                        .param("gitRepo", "https://github.com/Sai-333/agentbench")
                        .param("gitBranch", "cursor/selenium-regression-runs-8eb0")
                        .param("gitSha", "ABCDEFABCDEFABCDEFABCDEFABCDEFABCDEFABCD")
                        .param("suiteName", "selenium")
                        .param("startedAt", "2026-11-11T01:00:00Z")
                        .param("finishedAt", "2026-11-11T01:05:00Z")
                        .header("Authorization", "Bearer " + owner)
                        .contentType(MediaType.APPLICATION_XML)
                        .content(xml))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.scope").value("platform"))
                .andExpect(jsonPath("$.suiteName").value("selenium"))
                .andExpect(jsonPath("$.gitSha").value("abcdefabcdefabcdefabcdefabcdefabcdefabcd"))
                .andExpect(jsonPath("$.gitBranch").value("cursor/selenium-regression-runs-8eb0"))
                .andExpect(jsonPath("$.passed").value(1))
                .andExpect(jsonPath("$.failed").value(2))
                .andExpect(jsonPath("$.skipped").value(1))
                .andExpect(jsonPath("$.caseCount").value(4))
                .andExpect(jsonPath("$.startedAt").value("2026-11-11T01:00:00Z"))
                .andExpect(jsonPath("$.finishedAt").value("2026-11-11T01:05:00Z"))
                .andExpect(jsonPath("$.cases.length()").value(4))
                .andExpect(jsonPath("$.cases[0].name").value("tests.test_regression::test_ok"))
                .andExpect(jsonPath("$.cases[0].status").value("passed"))
                .andExpect(jsonPath("$.cases[0].durationMs").value(1250))
                .andExpect(jsonPath("$.cases[1].status").value("failed"))
                .andExpect(jsonPath("$.cases[1].message").value("AssertionError: boom"))
                .andExpect(jsonPath("$.cases[2].status").value("failed"))
                .andExpect(jsonPath("$.cases[2].message").value("exploded"))
                .andExpect(jsonPath("$.cases[3].status").value("skipped"))
                .andReturn();

        String runId = objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asText();

        mockMvc.perform(get("/api/v1/regression-runs/{id}", runId)
                        .header("Authorization", "Bearer " + owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.passed").value(1))
                .andExpect(jsonPath("$.failed").value(2))
                .andExpect(jsonPath("$.skipped").value(1))
                .andExpect(jsonPath("$.cases.length()").value(4));

        MvcResult listed = mockMvc.perform(get("/api/v1/regression-runs")
                        .header("Authorization", "Bearer " + owner))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode match = null;
        for (JsonNode row : objectMapper.readTree(listed.getResponse().getContentAsString())) {
            if (runId.equals(row.get("id").asText())) {
                match = row;
                break;
            }
        }
        if (match == null) {
            throw new AssertionError("Ingested run was not listed");
        }
        assertEquals(1, match.get("passed").asInt());
        assertEquals(2, match.get("failed").asInt());
        assertEquals(4, match.get("caseCount").asInt());
        assertEquals("platform", match.get("scope").asText());
        assertFalse(match.has("cases"));
    }

    @Test
    void adminIngestsPytestJsonWithoutTrustingSummary() throws Exception {
        String owner = register("owner-" + UUID.randomUUID() + "@example.com", "Owner");
        String admin = register("admin-" + UUID.randomUUID() + "@example.com", "Admin");
        UUID orgId = createOrg(owner, "Admin " + UUID.randomUUID(), "adm-" + UUID.randomUUID().toString().substring(0, 8));
        addMember(owner, orgId, meEmail(admin), "ADMIN");

        String json = """
                {
                  "gitRepo": "https://github.com/Sai-333/agentbench",
                  "gitBranch": "main",
                  "gitSha": "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                  "suiteName": "selenium",
                  "summary": {"passed": 50, "failed": 0, "skipped": 0, "total": 50},
                  "tests": [
                    {"nodeid": "tests/test_regression.py::test_ok", "outcome": "passed", "duration": 0.2},
                    {"nodeid": "tests/test_regression.py::test_bad", "outcome": "failed",
                     "call": {"longrepr": "AssertionError: no"}},
                    {"nodeid": "tests/test_regression.py::test_skip", "outcome": "skipped",
                     "setup": {"longrepr": "Skipped: blocked"}}
                  ]
                }
                """;

        mockMvc.perform(post("/api/v1/regression-runs")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.passed").value(1))
                .andExpect(jsonPath("$.failed").value(1))
                .andExpect(jsonPath("$.skipped").value(1))
                .andExpect(jsonPath("$.caseCount").value(3))
                .andExpect(jsonPath("$.cases[0].durationMs").value(200))
                .andExpect(jsonPath("$.cases[1].message").value("AssertionError: no"))
                .andExpect(jsonPath("$.cases[2].status").value("skipped"))
                .andExpect(jsonPath("$.ingestedBy").isString());
    }

    @Test
    void canonicalJsonKeepsDuplicateNamesAndRejectsBadMetadata() throws Exception {
        String owner = register("owner-" + UUID.randomUUID() + "@example.com", "Owner");
        createOrg(owner, "Canon " + UUID.randomUUID(), "can-" + UUID.randomUUID().toString().substring(0, 8));
        String json = """
                {
                  "gitRepo": "https://github.com/Sai-333/agentbench",
                  "gitBranch": "selenium-mock",
                  "gitSha": "cccccccccccccccccccccccccccccccccccccccc",
                  "suiteName": "selenium-mock",
                  "cases": [
                    {"name": "mock-seed::test_passes", "status": "passed", "durationMs": 120},
                    {"name": "mock-seed::test_passes", "status": "passed", "durationMs": 15},
                    {"name": "mock-seed::test_fails", "status": "failed", "durationMs": 80, "message": "mock failure"}
                  ]
                }
                """;

        mockMvc.perform(post("/api/v1/regression-runs")
                        .header("Authorization", "Bearer " + owner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.passed").value(2))
                .andExpect(jsonPath("$.failed").value(1))
                .andExpect(jsonPath("$.cases.length()").value(3))
                .andExpect(jsonPath("$.cases[0].name").value("mock-seed::test_passes"))
                .andExpect(jsonPath("$.cases[1].name").value("mock-seed::test_passes"))
                .andExpect(jsonPath("$.cases[2].message").value("mock failure"));

        mockMvc.perform(post("/api/v1/regression-runs")
                        .param("gitRepo", "https://github.com/Sai-333/agentbench")
                        .param("gitBranch", "main")
                        .param("gitSha", "not-a-sha")
                        .param("suiteName", "selenium")
                        .header("Authorization", "Bearer " + owner)
                        .contentType(MediaType.APPLICATION_XML)
                        .content("<testsuites></testsuites>"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/v1/regression-runs")
                        .param("gitRepo", "https://github.com/Sai-333/agentbench")
                        .param("gitBranch", "main")
                        .param("gitSha", "dddddddddddddddddddddddddddddddddddddddd")
                        .param("suiteName", "selenium")
                        .param("startedAt", "2026-11-11T02:00:00Z")
                        .param("finishedAt", "2026-11-11T01:00:00Z")
                        .header("Authorization", "Bearer " + owner)
                        .contentType(MediaType.APPLICATION_XML)
                        .content("<testsuites></testsuites>"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/v1/regression-runs")
                        .param("gitRepo", "https://github.com/Sai-333/agentbench")
                        .param("gitBranch", "main")
                        .param("gitSha", "dddddddddddddddddddddddddddddddddddddddd")
                        .param("suiteName", "selenium")
                        .header("Authorization", "Bearer " + owner)
                        .contentType(MediaType.APPLICATION_XML)
                        .content("""
                                <?xml version="1.0"?>
                                <!DOCTYPE foo [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
                                <testsuites><testsuite><testcase name="a">&xxe;</testcase></testsuite></testsuites>
                                """))
                .andExpect(status().isBadRequest());

        mockMvc.perform(get("/api/v1/regression-runs/{id}", UUID.randomUUID())
                        .header("Authorization", "Bearer " + owner))
                .andExpect(status().isNotFound());
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

    private void addMember(String token, UUID orgId, String email, String role) throws Exception {
        mockMvc.perform(post("/api/v1/orgs/{orgId}/members", orgId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","role":"%s"}
                                """.formatted(email, role)))
                .andExpect(status().isCreated());
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
