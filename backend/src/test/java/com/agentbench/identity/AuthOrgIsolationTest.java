package com.agentbench.identity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
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

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests: auth flows + org isolation (org A cannot read org B projects).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class AuthOrgIsolationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("agentbench")
            .withUsername("agentbench")
            .withPassword("agentbench");

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.autoconfigure.exclude", () -> String.join(",",
                "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration",
                "org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration",
                "org.springframework.boot.autoconfigure.amqp.RabbitAutoConfiguration"));
        registry.add("management.health.redis.enabled", () -> "false");
        registry.add("management.health.rabbit.enabled", () -> "false");
        registry.add("spring.rabbitmq.listener.simple.auto-startup", () -> "false");
        registry.add("agentbench.jwt.secret", () -> "test-only-jwt-secret-key-32chars-min!!");
        registry.add("agentbench.secrets.encryption-key", () -> "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=");
        registry.add("agentbench.github.validation-enabled", () -> "false");
        registry.add("agentbench.artifacts.root", () -> System.getProperty("java.io.tmpdir") + "/agentbench-it");
        registry.add("logging.structured.format.console", () -> "");
    }

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @BeforeAll
    static void requireDocker() {
        // Testcontainers handles skip via disabledWithoutDocker when Docker is absent.
    }

    @Test
    void registerLoginAndIsolateProjectsAcrossOrgs() throws Exception {
        String tokenA = register("alice-" + UUID.randomUUID() + "@example.com", "Alice");
        String tokenB = register("bob-" + UUID.randomUUID() + "@example.com", "Bob");

        UUID orgA = createOrg(tokenA, "Org A " + UUID.randomUUID(), "org-a-" + UUID.randomUUID().toString().substring(0, 8));
        UUID orgB = createOrg(tokenB, "Org B " + UUID.randomUUID(), "org-b-" + UUID.randomUUID().toString().substring(0, 8));
        assertNotEquals(orgA, orgB);

        UUID projectA = createProject(tokenA, orgA, "Alpha");
        createProject(tokenB, orgB, "Beta");

        mockMvc.perform(get("/api/v1/orgs/{orgId}/projects", orgA)
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(projectA.toString()))
                .andExpect(jsonPath("$[0].name").value("Alpha"));

        // Org B member cannot list Org A projects
        mockMvc.perform(get("/api/v1/orgs/{orgId}/projects", orgA)
                        .header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isForbidden());

        // Org B member cannot fetch Org A project by id
        mockMvc.perform(get("/api/v1/orgs/{orgId}/projects/{projectId}", orgA, projectA)
                        .header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isForbidden());

        // Secrets never echo plaintext
        MvcResult secretResult = mockMvc.perform(post("/api/v1/orgs/{orgId}/secrets", orgA)
                        .header("Authorization", "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"github-clone","purpose":"GIT","plaintext":"ghp_super_secret_token"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("github-clone"))
                .andExpect(jsonPath("$.plaintext").doesNotExist())
                .andExpect(jsonPath("$.ciphertext").doesNotExist())
                .andReturn();
        String secretBody = secretResult.getResponse().getContentAsString();
        if (secretBody.contains("ghp_super_secret_token")) {
            throw new AssertionError("Secret plaintext leaked in response");
        }
    }

    @Test
    void viewerCannotCreateProject() throws Exception {
        String ownerToken = register("owner-" + UUID.randomUUID() + "@example.com", "Owner");
        String viewerToken = register("viewer-" + UUID.randomUUID() + "@example.com", "Viewer");
        UUID orgId = createOrg(ownerToken, "RBAC Org " + UUID.randomUUID(), "rbac-" + UUID.randomUUID().toString().substring(0, 8));

        mockMvc.perform(post("/api/v1/orgs/{orgId}/members", orgId)
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","role":"VIEWER"}
                                """.formatted(meEmail(viewerToken))))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/orgs/{orgId}/projects", orgId)
                        .header("Authorization", "Bearer " + viewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Nope","description":""}
                                """))
                .andExpect(status().isForbidden());
    }

    private String register(String email, String displayName) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"password123","displayName":"%s"}
                                """.formatted(email, displayName)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.accessToken").isString())
                .andExpect(jsonPath("$.refreshToken").isString())
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
