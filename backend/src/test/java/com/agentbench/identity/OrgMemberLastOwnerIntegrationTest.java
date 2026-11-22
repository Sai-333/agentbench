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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * Last-OWNER and demotion edges on PUT/DELETE /api/v1/orgs/{orgId}/members/{userId}.
 * Rules live in {@link com.agentbench.identity.service.OrganizationService}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class OrgMemberLastOwnerIntegrationTest {

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
    void cannotDemoteTheLastOwner() throws Exception {
        String ownerToken = register("sole-owner");
        UUID orgId = createOrg(ownerToken);
        UUID ownerId = meId(ownerToken);

        MvcResult result = mockMvc.perform(put("/api/v1/orgs/{orgId}/members/{userId}", orgId, ownerId)
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("role", "ADMIN"))))
                .andReturn();
        assertStatus(result, 409);
        assertProblemCode(result, "STATE_CONFLICT");
        assertMemberRole(ownerToken, orgId, ownerId, "OWNER");
    }

    @Test
    void cannotRemoveTheLastOwner() throws Exception {
        String ownerToken = register("last-owner");
        UUID orgId = createOrg(ownerToken);
        UUID ownerId = meId(ownerToken);

        MvcResult result = mockMvc.perform(delete("/api/v1/orgs/{orgId}/members/{userId}", orgId, ownerId)
                        .header("Authorization", "Bearer " + ownerToken))
                .andReturn();
        assertStatus(result, 409);
        assertProblemCode(result, "STATE_CONFLICT");
        assertMemberRole(ownerToken, orgId, ownerId, "OWNER");
    }

    @Test
    void demotionSucceedsWhenAnotherOwnerRemains() throws Exception {
        Fixture fixture = twoOwners();

        MvcResult result = mockMvc.perform(put("/api/v1/orgs/{orgId}/members/{userId}", fixture.orgId(), fixture.secondOwnerId())
                        .header("Authorization", "Bearer " + fixture.firstOwnerToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("role", "ADMIN"))))
                .andReturn();
        assertStatus(result, 200);
        JsonNode body = read(result);
        if (!"ADMIN".equals(body.path("role").asText())) {
            throw new AssertionError("expected demoted member role ADMIN");
        }
        assertMemberRole(fixture.firstOwnerToken(), fixture.orgId(), fixture.secondOwnerId(), "ADMIN");
        assertMemberRole(fixture.firstOwnerToken(), fixture.orgId(), fixture.firstOwnerId(), "OWNER");
    }

    @Test
    void removeSucceedsWhenAnotherOwnerRemains() throws Exception {
        Fixture fixture = twoOwners();

        MvcResult result = mockMvc.perform(delete(
                        "/api/v1/orgs/{orgId}/members/{userId}", fixture.orgId(), fixture.secondOwnerId())
                        .header("Authorization", "Bearer " + fixture.firstOwnerToken()))
                .andReturn();
        assertStatus(result, 204);
        if (findMember(listMembers(fixture.firstOwnerToken(), fixture.orgId()), fixture.secondOwnerId()) != null) {
            throw new AssertionError("removed owner was still listed");
        }
        assertMemberRole(fixture.firstOwnerToken(), fixture.orgId(), fixture.firstOwnerId(), "OWNER");
    }

    @Test
    void adminCannotDemoteOrRemoveAnOwner() throws Exception {
        String ownerToken = register("owner-vs-admin");
        String adminToken = register("admin-vs-owner");
        UUID orgId = createOrg(ownerToken);
        UUID ownerId = meId(ownerToken);
        addMember(ownerToken, orgId, meEmail(adminToken), "ADMIN");

        MvcResult demote = mockMvc.perform(put("/api/v1/orgs/{orgId}/members/{userId}", orgId, ownerId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("role", "VIEWER"))))
                .andReturn();
        assertStatus(demote, 403);
        assertProblemCode(demote, "AUTH_FORBIDDEN");

        MvcResult remove = mockMvc.perform(delete("/api/v1/orgs/{orgId}/members/{userId}", orgId, ownerId)
                        .header("Authorization", "Bearer " + adminToken))
                .andReturn();
        assertStatus(remove, 403);
        assertProblemCode(remove, "AUTH_FORBIDDEN");

        assertMemberRole(ownerToken, orgId, ownerId, "OWNER");
    }

    private Fixture twoOwners() throws Exception {
        String firstOwnerToken = register("owner-a");
        String secondOwnerToken = register("owner-b");
        UUID orgId = createOrg(firstOwnerToken);
        UUID firstOwnerId = meId(firstOwnerToken);
        UUID secondOwnerId = meId(secondOwnerToken);

        addMember(firstOwnerToken, orgId, meEmail(secondOwnerToken), "ADMIN");
        MvcResult promote = mockMvc.perform(put("/api/v1/orgs/{orgId}/members/{userId}", orgId, secondOwnerId)
                        .header("Authorization", "Bearer " + firstOwnerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("role", "OWNER"))))
                .andReturn();
        assertStatus(promote, 200);
        assertMemberRole(firstOwnerToken, orgId, secondOwnerId, "OWNER");
        return new Fixture(orgId, firstOwnerToken, firstOwnerId, secondOwnerId);
    }

    private String register(String label) throws Exception {
        return register(label + "-" + UUID.randomUUID() + "@example.com", label);
    }

    private String register(String email, String displayName) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "email", email,
                                "password", "password123",
                                "displayName", displayName))))
                .andReturn();
        assertStatus(result, 201);
        return read(result).path("accessToken").asText();
    }

    private UUID createOrg(String token) throws Exception {
        String slug = "own-" + UUID.randomUUID().toString().substring(0, 8);
        MvcResult result = mockMvc.perform(post("/api/v1/orgs")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "Owners " + slug, "slug", slug))))
                .andReturn();
        assertStatus(result, 201);
        return UUID.fromString(read(result).path("id").asText());
    }

    private void addMember(String token, UUID orgId, String email, String role) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/orgs/{orgId}/members", orgId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("email", email, "role", role))))
                .andReturn();
        assertStatus(result, 201);
    }

    private JsonNode listMembers(String token, UUID orgId) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/orgs/{orgId}/members", orgId)
                        .header("Authorization", "Bearer " + token))
                .andReturn();
        assertStatus(result, 200);
        return read(result);
    }

    private void assertMemberRole(String token, UUID orgId, UUID userId, String expectedRole) throws Exception {
        JsonNode member = findMember(listMembers(token, orgId), userId);
        if (member == null) {
            throw new AssertionError("member " + userId + " was missing from the org");
        }
        if (!expectedRole.equals(member.path("role").asText())) {
            throw new AssertionError("expected role " + expectedRole + " but was " + member.path("role").asText());
        }
    }

    private static JsonNode findMember(JsonNode listed, UUID userId) {
        if (!listed.isArray()) {
            throw new AssertionError("member list was not an array");
        }
        for (JsonNode item : listed) {
            if (userId.toString().equals(item.path("userId").asText())) {
                return item;
            }
        }
        return null;
    }

    private UUID meId(String token) throws Exception {
        return UUID.fromString(me(token).path("id").asText());
    }

    private String meEmail(String token) throws Exception {
        return me(token).path("email").asText();
    }

    private JsonNode me(String token) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/me")
                        .header("Authorization", "Bearer " + token))
                .andReturn();
        assertStatus(result, 200);
        return read(result);
    }

    private void assertProblemCode(MvcResult result, String code) throws Exception {
        if (!code.equals(read(result).path("code").asText())) {
            throw new AssertionError("expected error code " + code + " but was " + read(result).path("code").asText());
        }
    }

    private static void assertStatus(MvcResult result, int expected) {
        int actual = result.getResponse().getStatus();
        if (actual != expected) {
            throw new AssertionError("expected HTTP " + expected + " but got " + actual
                    + " body=" + result.getResponse().getContentAsString());
        }
    }

    private JsonNode read(MvcResult result) throws Exception {
        String body = result.getResponse().getContentAsString();
        if (body == null || body.isBlank()) {
            return objectMapper.createObjectNode();
        }
        return objectMapper.readTree(body);
    }

    private byte[] json(Map<String, String> body) throws Exception {
        return objectMapper.writeValueAsBytes(body);
    }

    private record Fixture(UUID orgId, String firstOwnerToken, UUID firstOwnerId, UUID secondOwnerId) {
    }
}
