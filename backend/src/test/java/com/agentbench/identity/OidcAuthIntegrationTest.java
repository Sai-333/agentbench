package com.agentbench.identity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import org.junit.jupiter.api.BeforeEach;
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

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * OIDC authorization-code login against WireMock IdP endpoints (no real Google/GitHub).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class OidcAuthIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("agentbench")
            .withUsername("agentbench")
            .withPassword("agentbench");

    @RegisterExtension
    static WireMockExtension idp = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
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
        registry.add("agentbench.artifacts.root", () -> System.getProperty("java.io.tmpdir") + "/agentbench-oidc-it");
        registry.add("logging.structured.format.console", () -> "");

        registry.add("agentbench.oidc.success-redirect-url", () -> "http://localhost:3000/");
        registry.add("agentbench.oidc.google.enabled", () -> "true");
        registry.add("agentbench.oidc.google.client-id", () -> "google-client-id");
        registry.add("agentbench.oidc.google.client-secret", () -> "google-client-secret");
        registry.add("agentbench.oidc.google.redirect-uri", () -> "http://localhost:8080/api/v1/auth/oidc/google/callback");
        registry.add("agentbench.oidc.google.authorization-uri", () -> idp.baseUrl() + "/oauth2/auth");
        registry.add("agentbench.oidc.google.token-uri", () -> idp.baseUrl() + "/oauth2/token");
        registry.add("agentbench.oidc.google.user-info-uri", () -> idp.baseUrl() + "/oauth2/userinfo");
        registry.add("agentbench.oidc.google.scopes", () -> "openid email profile");

        registry.add("agentbench.oidc.github.enabled", () -> "true");
        registry.add("agentbench.oidc.github.client-id", () -> "github-client-id");
        registry.add("agentbench.oidc.github.client-secret", () -> "github-client-secret");
        registry.add("agentbench.oidc.github.redirect-uri", () -> "http://localhost:8080/api/v1/auth/oidc/github/callback");
        registry.add("agentbench.oidc.github.authorization-uri", () -> idp.baseUrl() + "/login/oauth/authorize");
        registry.add("agentbench.oidc.github.token-uri", () -> idp.baseUrl() + "/login/oauth/access_token");
        registry.add("agentbench.oidc.github.user-info-uri", () -> idp.baseUrl() + "/user");
        registry.add("agentbench.oidc.github.email-uri", () -> idp.baseUrl() + "/user/emails");
        registry.add("agentbench.oidc.github.scopes", () -> "read:user user:email");
    }

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @BeforeEach
    void resetIdp() {
        idp.resetAll();
    }

    @Test
    void providersListsConfiguredOidcBesideLocal() throws Exception {
        mockMvc.perform(get("/api/v1/auth/providers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.providers[?(@.id=='local')]").exists())
                .andExpect(jsonPath("$.providers[?(@.id=='google')].authorizePath")
                        .value("/api/v1/auth/oidc/google/authorize"))
                .andExpect(jsonPath("$.providers[?(@.id=='github')].authorizePath")
                        .value("/api/v1/auth/oidc/github/authorize"));
    }

    @Test
    void googleAuthorizationCodeIssuesSameJwtPairAndLinksUser() throws Exception {
        String email = "google-user-" + UUID.randomUUID() + "@example.com";
        stubGoogleTokenAndUserInfo(email, "google-sub-1", "Google User");

        String state = startAuthorizeAndExtractState("google");
        MvcResult tokenResult = mockMvc.perform(post("/api/v1/auth/oidc/google/token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"google-auth-code","state":"%s"}
                                """.formatted(state)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isString())
                .andExpect(jsonPath("$.refreshToken").isString())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andReturn();

        JsonNode tokens = objectMapper.readTree(tokenResult.getResponse().getContentAsString());
        String access = tokens.get("accessToken").asText();
        assertFalse(tokenResult.getResponse().getContentAsString().contains("google-client-secret"));

        mockMvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + access))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(email))
                .andExpect(jsonPath("$.displayName").value("Google User"));

        // Second login with same subject reuses the linked local user
        idp.resetAll();
        stubGoogleTokenAndUserInfo(email, "google-sub-1", "Google User");
        String state2 = startAuthorizeAndExtractState("google");
        MvcResult second = mockMvc.perform(post("/api/v1/auth/oidc/google/token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"google-auth-code-2","state":"%s"}
                                """.formatted(state2)))
                .andExpect(status().isOk())
                .andReturn();
        String access2 = objectMapper.readTree(second.getResponse().getContentAsString()).get("accessToken").asText();
        MvcResult me1 = mockMvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + access)).andReturn();
        MvcResult me2 = mockMvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + access2)).andReturn();
        assertEquals(
                objectMapper.readTree(me1.getResponse().getContentAsString()).get("id").asText(),
                objectMapper.readTree(me2.getResponse().getContentAsString()).get("id").asText()
        );

        idp.verify(postRequestedFor(urlEqualTo("/oauth2/token")).withRequestBody(containing("client_id=google-client-id")));
        idp.verify(getRequestedFor(urlEqualTo("/oauth2/userinfo")));
    }

    @Test
    void githubUsesEmailsEndpointWhenProfileEmailMissing() throws Exception {
        String email = "github-user-" + UUID.randomUUID() + "@users.noreply.example.com";
        idp.stubFor(com.github.tomakehurst.wiremock.client.WireMock.post(urlEqualTo("/login/oauth/access_token"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"access_token":"gho_test_token","token_type":"bearer","scope":"read:user,user:email"}
                                """)));
        idp.stubFor(com.github.tomakehurst.wiremock.client.WireMock.get(urlEqualTo("/user"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"id":4242,"login":"octo","name":"Octo Cat","email":null}
                                """)));
        idp.stubFor(com.github.tomakehurst.wiremock.client.WireMock.get(urlEqualTo("/user/emails"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                [
                                  {"email":"other@example.com","primary":false,"verified":true},
                                  {"email":"%s","primary":true,"verified":true}
                                ]
                                """.formatted(email))));

        String state = startAuthorizeAndExtractState("github");
        MvcResult tokenResult = mockMvc.perform(post("/api/v1/auth/oidc/github/token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"github-auth-code","state":"%s"}
                                """.formatted(state)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isString())
                .andReturn();

        String access = objectMapper.readTree(tokenResult.getResponse().getContentAsString()).get("accessToken").asText();
        mockMvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + access))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(email))
                .andExpect(jsonPath("$.displayName").value("Octo Cat"));

        idp.verify(getRequestedFor(urlEqualTo("/user/emails"))
                .withHeader("Authorization", equalTo("Bearer gho_test_token")));
    }

    @Test
    void browserCallbackRedirectsWithTokenFragment() throws Exception {
        String email = "callback-" + UUID.randomUUID() + "@example.com";
        stubGoogleTokenAndUserInfo(email, "google-sub-callback", "Callback User");
        String state = startAuthorizeAndExtractState("google");

        MvcResult result = mockMvc.perform(get("/api/v1/auth/oidc/google/callback")
                        .param("code", "google-browser-code")
                        .param("state", state))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", org.hamcrest.Matchers.startsWith("http://localhost:3000/#")))
                .andReturn();

        String location = result.getResponse().getHeader("Location");
        assertTrue(location.contains("access_token="));
        assertTrue(location.contains("refresh_token="));
        assertFalse(location.contains("google-client-secret"));
    }

    @Test
    void rejectsReuseOfOidcState() throws Exception {
        stubGoogleTokenAndUserInfo("reuse-" + UUID.randomUUID() + "@example.com", "sub-reuse", "Reuse");
        String state = startAuthorizeAndExtractState("google");
        mockMvc.perform(post("/api/v1/auth/oidc/google/token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"c1","state":"%s"}
                                """.formatted(state)))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/auth/oidc/google/token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"c2","state":"%s"}
                                """.formatted(state)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void linksOidcIdentityToExistingPasswordUser() throws Exception {
        String email = "link-" + UUID.randomUUID() + "@example.com";
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"password123","displayName":"Local First"}
                                """.formatted(email)))
                .andExpect(status().isCreated());

        stubGoogleTokenAndUserInfo(email, "google-link-sub", "Local First");
        String state = startAuthorizeAndExtractState("google");
        MvcResult oidc = mockMvc.perform(post("/api/v1/auth/oidc/google/token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"link-code","state":"%s"}
                                """.formatted(state)))
                .andExpect(status().isOk())
                .andReturn();
        String access = objectMapper.readTree(oidc.getResponse().getContentAsString()).get("accessToken").asText();
        mockMvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + access))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(email))
                .andExpect(jsonPath("$.displayName").value("Local First"));

        // Password login still works
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"password123","provider":"local"}
                                """.formatted(email)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isString());
    }

    private void stubGoogleTokenAndUserInfo(String email, String subject, String name) {
        idp.stubFor(com.github.tomakehurst.wiremock.client.WireMock.post(urlEqualTo("/oauth2/token"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"access_token":"ya29.test","token_type":"Bearer","expires_in":3600}
                                """)));
        idp.stubFor(com.github.tomakehurst.wiremock.client.WireMock.get(urlEqualTo("/oauth2/userinfo"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"sub":"%s","email":"%s","email_verified":true,"name":"%s"}
                                """.formatted(subject, email, name))));
    }

    private String startAuthorizeAndExtractState(String provider) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/auth/oidc/{provider}/authorize", provider))
                .andExpect(status().isFound())
                .andReturn();
        String location = result.getResponse().getHeader("Location");
        Map<String, String> query = queryParams(URI.create(location));
        assertTrue(query.containsKey("state"));
        assertTrue(query.containsKey("client_id"));
        return query.get("state");
    }

    private static Map<String, String> queryParams(URI uri) {
        String raw = uri.getRawQuery();
        if (raw == null || raw.isBlank()) {
            return Map.of();
        }
        return Arrays.stream(raw.split("&"))
                .map(part -> part.split("=", 2))
                .collect(Collectors.toMap(
                        parts -> URLDecoder.decode(parts[0], StandardCharsets.UTF_8),
                        parts -> parts.length > 1 ? URLDecoder.decode(parts[1], StandardCharsets.UTF_8) : ""
                ));
    }
}
