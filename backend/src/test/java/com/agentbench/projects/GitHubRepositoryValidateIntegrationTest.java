package com.agentbench.projects;

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

import java.util.Map;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * POST /api/v1/orgs/{orgId}/projects/{projectId}/repository/validate against the real
 * GitHub API. A public owner/repo is found. A missing repo is not found.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class GitHubRepositoryValidateIntegrationTest {

    private static final String PUBLIC_REPO = "https://github.com/octocat/Hello-World";
    private static final String MISSING_REPO = "https://github.com/octocat/agentbench-missing-repo-not-real";

    @Container
    static PostgreSQLContainer<?> postgres = PostgresTestSupport.postgres();

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        PostgresTestSupport.registerRlsAwareDatasource(registry, postgres);
        registry.add("agentbench.github.token", () -> "");
        registry.add("agentbench.github.validation-enabled", () -> "true");
    }

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Test
    void publicRepositoryIsFoundAndMissingRepositoryIsNotFound() throws Exception {
        String token = register();
        UUID orgId = createOrg(token);
        UUID projectId = createProject(token, orgId);

        connect(token, orgId, projectId, PUBLIC_REPO);
        JsonNode found = read(validate(token, orgId, projectId));
        assertOutcome(found, "VALID", "Repository found");

        connect(token, orgId, projectId, MISSING_REPO);
        JsonNode missing = read(validate(token, orgId, projectId));
        assertOutcome(missing, "INVALID", "Repository not found");
    }

    private MvcResult validate(String token, UUID orgId, UUID projectId) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/orgs/{orgId}/projects/{projectId}/repository/validate", orgId, projectId)
                        .header("Authorization", "Bearer " + token))
                .andReturn();
        assertStatus(result, 200);
        assertNoToken(result.getResponse().getContentAsString());
        return result;
    }

    private void connect(String token, UUID orgId, UUID projectId, String gitUrl) throws Exception {
        MvcResult result = mockMvc.perform(put("/api/v1/orgs/{orgId}/projects/{projectId}/repository", orgId, projectId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "gitUrl", gitUrl,
                                "defaultBranch", "master",
                                "validate", false))))
                .andReturn();
        assertStatus(result, 200);
        assertNoToken(result.getResponse().getContentAsString());
    }

    private String register() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "email", "repo-" + UUID.randomUUID() + "@example.com",
                                "password", "password123",
                                "displayName", "Repo"))))
                .andReturn();
        assertStatus(result, 201);
        return read(result).path("accessToken").asText();
    }

    private UUID createOrg(String token) throws Exception {
        String slug = "repo-" + UUID.randomUUID().toString().substring(0, 8);
        MvcResult result = mockMvc.perform(post("/api/v1/orgs")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "Repos " + slug, "slug", slug))))
                .andReturn();
        assertStatus(result, 201);
        return UUID.fromString(read(result).path("id").asText());
    }

    private UUID createProject(String token, UUID orgId) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/orgs/{orgId}/projects", orgId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "Repo project", "description", "validation"))))
                .andReturn();
        assertStatus(result, 201);
        return UUID.fromString(read(result).path("id").asText());
    }

    private void assertOutcome(JsonNode body, String status, String message) {
        if (!status.equals(body.path("validationStatus").asText())) {
            throw new AssertionError("expected validation status " + status);
        }
        if (!message.equals(body.path("validationMessage").asText())) {
            throw new AssertionError("expected validation message " + message);
        }
        if (body.path("lastValidatedAt").asText("").isBlank()) {
            throw new AssertionError("validation did not record a time");
        }
    }

    private static void assertNoToken(String body) {
        String lower = body.toLowerCase();
        if (lower.contains("ghp_") || lower.contains("github_pat_") || lower.contains("bearer ")
                || body.contains("AGENTBENCH_GITHUB_TOKEN")) {
            throw new AssertionError("response included a token");
        }
    }

    private static void assertStatus(MvcResult result, int expected) {
        int actual = result.getResponse().getStatus();
        if (actual != expected) {
            throw new AssertionError("expected HTTP " + expected + " but got " + actual);
        }
    }

    private JsonNode read(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private byte[] json(Map<String, ?> body) throws Exception {
        return objectMapper.writeValueAsBytes(body);
    }
}
