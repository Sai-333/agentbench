package com.agentbench.identity;

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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * POST /api/v1/auth/refresh and /api/v1/auth/logout against the real auth service and Postgres.
 * A refresh rotates the token, a reused revoked refresh token is rejected, and logout revokes it.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class AuthRefreshLogoutIntegrationTest {

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
    void refreshRotatesTheTokenAndRejectsReuse() throws Exception {
        Issued first = register();
        Issued rotated = refresh(first.refreshToken());

        assertRotated(first.accessToken(), rotated.accessToken(), "access token");
        assertRotated(first.refreshToken(), rotated.refreshToken(), "refresh token");
        assertSameUser(rotated.accessToken(), first.email());
        assertRefreshRejected(first.refreshToken());

        Issued rotatedAgain = refresh(rotated.refreshToken());
        assertRotated(rotated.refreshToken(), rotatedAgain.refreshToken(), "refresh token");
        assertRotated(rotated.accessToken(), rotatedAgain.accessToken(), "access token");
        assertRefreshRejected(rotated.refreshToken());
        assertSameUser(rotatedAgain.accessToken(), first.email());
    }

    @Test
    void logoutWithRefreshTokenRevokesIt() throws Exception {
        Issued issued = register();

        MvcResult result = mockMvc.perform(post("/api/v1/auth/logout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("refreshToken", issued.refreshToken()))))
                .andReturn();
        assertNoContent(result, issued.refreshToken());
        assertRefreshRejected(issued.refreshToken());
    }

    @Test
    void authenticatedLogoutRevokesTheRefreshToken() throws Exception {
        Issued issued = register();

        MvcResult result = mockMvc.perform(post("/api/v1/auth/logout")
                        .header("Authorization", "Bearer " + issued.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andReturn();
        assertNoContent(result, issued.refreshToken());
        assertRefreshRejected(issued.refreshToken());
    }

    private Issued register() throws Exception {
        String email = "refresh-" + UUID.randomUUID() + "@example.com";
        MvcResult result = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "email", email,
                                "password", "password123",
                                "displayName", "Refresh User"))))
                .andReturn();
        if (result.getResponse().getStatus() != 201) {
            throw new AssertionError("Expected register to return 201 but got " + result.getResponse().getStatus());
        }
        return readIssued(result, email);
    }

    private Issued refresh(String refreshToken) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("refreshToken", refreshToken))))
                .andReturn();
        if (result.getResponse().getStatus() != 200) {
            throw new AssertionError("Expected refresh to return 200 but got " + result.getResponse().getStatus());
        }
        String body = result.getResponse().getContentAsString();
        if (body.contains(refreshToken)) {
            throw new AssertionError("Refresh response echoed the presented refresh token");
        }
        return readIssued(result, null);
    }

    private void assertRefreshRejected(String refreshToken) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("refreshToken", refreshToken))))
                .andReturn();
        String body = result.getResponse().getContentAsString();
        if (body.contains(refreshToken)) {
            throw new AssertionError("Rejected refresh response included the refresh token");
        }
        if (result.getResponse().getStatus() != 401) {
            throw new AssertionError("Expected revoked refresh token to be rejected with 401 but got "
                    + result.getResponse().getStatus());
        }
        JsonNode node = objectMapper.readTree(body);
        if (!"AUTH_UNAUTHORIZED".equals(node.path("code").asText())) {
            throw new AssertionError("Expected AUTH_UNAUTHORIZED");
        }
        if (!"Invalid refresh token".equals(node.path("detail").asText())) {
            throw new AssertionError("Expected invalid refresh token detail");
        }
    }

    private void assertSameUser(String accessToken, String email) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/me")
                        .header("Authorization", "Bearer " + accessToken))
                .andReturn();
        if (result.getResponse().getStatus() != 200) {
            throw new AssertionError("Expected the rotated access token to load the current user");
        }
        String actual = objectMapper.readTree(result.getResponse().getContentAsString()).path("email").asText();
        if (!email.equals(actual)) {
            throw new AssertionError("Rotated access token did not identify the same user");
        }
    }

    private Issued readIssued(MvcResult result, String email) throws Exception {
        JsonNode node = objectMapper.readTree(result.getResponse().getContentAsString());
        String accessToken = node.path("accessToken").asText("");
        String refreshToken = node.path("refreshToken").asText("");
        if (accessToken.isBlank() || refreshToken.isBlank()) {
            throw new AssertionError("Token response missing access or refresh token");
        }
        if (!"Bearer".equals(node.path("tokenType").asText())) {
            throw new AssertionError("Expected a Bearer token");
        }
        if (node.path("expiresInSeconds").asLong() <= 0) {
            throw new AssertionError("Expected a positive access-token lifetime");
        }
        return new Issued(email, accessToken, refreshToken);
    }

    private byte[] json(Map<String, String> body) throws Exception {
        return objectMapper.writeValueAsBytes(body);
    }

    private static void assertNoContent(MvcResult result, String refreshToken) throws Exception {
        String body = result.getResponse().getContentAsString();
        if (refreshToken != null && body.contains(refreshToken)) {
            throw new AssertionError("Logout response included the refresh token");
        }
        if (result.getResponse().getStatus() != 204) {
            throw new AssertionError("Expected logout to return 204 but got " + result.getResponse().getStatus());
        }
    }

    private static void assertRotated(String previous, String next, String what) {
        if (previous == null || next == null || previous.isBlank() || next.isBlank() || previous.equals(next)) {
            throw new AssertionError(what + " was not rotated");
        }
    }

    private record Issued(String email, String accessToken, String refreshToken) {
    }
}
