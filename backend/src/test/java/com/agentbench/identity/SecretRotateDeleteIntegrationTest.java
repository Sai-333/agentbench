package com.agentbench.identity;

import com.agentbench.identity.service.SecretService;
import com.agentbench.platform.error.ApiException;
import com.agentbench.platform.rls.RlsContext;
import com.agentbench.support.PostgresTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
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

/**
 * Secret rotate and delete against the real {@link SecretService} and Postgres.
 * Create is covered elsewhere. Responses must not include secret values.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class SecretRotateDeleteIntegrationTest {

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

    @Autowired
    SecretService secretService;

    @Test
    void rotateReplacesTheStoredSecretAndDoesNotEchoIt() throws Exception {
        String token = register("rotate-owner");
        UUID orgId = createOrg(token);
        String original = secretValue();
        String replacement = secretValue();
        Created created = createSecret(token, orgId, "agent-key", original);

        MvcResult result = mockMvc.perform(post("/api/v1/orgs/{orgId}/secrets/{secretId}/rotate", orgId, created.id())
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("plaintext", replacement))))
                .andReturn();
        assertStatus(result, 200);
        assertNoSecretMaterial(result, original, replacement);
        JsonNode body = read(result);
        if (!created.id().toString().equals(body.path("id").asText())) {
            throw new AssertionError("rotate changed the secret id");
        }
        if (!"agent-key".equals(body.path("name").asText()) || !"AGENT".equals(body.path("purpose").asText())) {
            throw new AssertionError("rotate changed the secret name or purpose");
        }
        if (body.path("rotatedAt").asText("").isBlank()) {
            throw new AssertionError("rotate did not record rotatedAt");
        }
        assertStored(orgId, created.id(), replacement, original);

        JsonNode listed = listSecrets(token, orgId);
        JsonNode match = findListed(listed, created.id());
        if (match == null || match.path("rotatedAt").asText("").isBlank()) {
            throw new AssertionError("rotated secret was missing from the metadata list");
        }
        assertNoSecretMaterial(listed.toString(), original, replacement);
    }

    @Test
    void deleteRemovesTheSecret() throws Exception {
        String token = register("delete-owner");
        UUID orgId = createOrg(token);
        String value = secretValue();
        Created created = createSecret(token, orgId, "to-delete", value);

        MvcResult result = mockMvc.perform(delete("/api/v1/orgs/{orgId}/secrets/{secretId}", orgId, created.id())
                        .header("Authorization", "Bearer " + token))
                .andReturn();
        assertStatus(result, 204);
        assertNoSecretMaterial(result, value);
        if (findListed(listSecrets(token, orgId), created.id()) != null) {
            throw new AssertionError("deleted secret was still listed");
        }
        assertMissing(orgId, created.id());

        MvcResult again = mockMvc.perform(delete("/api/v1/orgs/{orgId}/secrets/{secretId}", orgId, created.id())
                        .header("Authorization", "Bearer " + token))
                .andReturn();
        assertStatus(again, 404);
        assertProblemCode(again, "RESOURCE_NOT_FOUND");

        MvcResult rotate = mockMvc.perform(post("/api/v1/orgs/{orgId}/secrets/{secretId}/rotate", orgId, created.id())
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("plaintext", secretValue()))))
                .andReturn();
        assertStatus(rotate, 404);
        assertProblemCode(rotate, "RESOURCE_NOT_FOUND");
    }

    @Test
    void developerCanRotateButCannotDelete() throws Exception {
        String owner = register("role-owner");
        String developerEmail = "developer-" + UUID.randomUUID() + "@example.com";
        String developer = register(developerEmail, "Developer");
        UUID orgId = createOrg(owner);
        String original = secretValue();
        String replacement = secretValue();
        Created created = createSecret(owner, orgId, "dev-key", original);
        addMember(owner, orgId, developerEmail, "DEVELOPER");

        MvcResult rotated = mockMvc.perform(post("/api/v1/orgs/{orgId}/secrets/{secretId}/rotate", orgId, created.id())
                        .header("Authorization", "Bearer " + developer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("plaintext", replacement))))
                .andReturn();
        assertStatus(rotated, 200);
        assertNoSecretMaterial(rotated, original, replacement);
        assertStored(orgId, created.id(), replacement, original);

        MvcResult denied = mockMvc.perform(delete("/api/v1/orgs/{orgId}/secrets/{secretId}", orgId, created.id())
                        .header("Authorization", "Bearer " + developer))
                .andReturn();
        assertStatus(denied, 403);
        assertProblemCode(denied, "AUTH_FORBIDDEN");
        assertNoSecretMaterial(denied, original, replacement);
        assertStored(orgId, created.id(), replacement, original);
    }

    @Test
    void anotherOrganizationCannotRotateOrDelete() throws Exception {
        String ownerA = register("org-a");
        String ownerB = register("org-b");
        UUID orgA = createOrg(ownerA);
        createOrg(ownerB);
        String value = secretValue();
        Created created = createSecret(ownerA, orgA, "isolated-key", value);

        MvcResult rotate = mockMvc.perform(post("/api/v1/orgs/{orgId}/secrets/{secretId}/rotate", orgA, created.id())
                        .header("Authorization", "Bearer " + ownerB)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("plaintext", secretValue()))))
                .andReturn();
        assertStatus(rotate, 403);
        assertProblemCode(rotate, "AUTH_FORBIDDEN");
        assertNoSecretMaterial(rotate, value);

        MvcResult delete = mockMvc.perform(delete("/api/v1/orgs/{orgId}/secrets/{secretId}", orgA, created.id())
                        .header("Authorization", "Bearer " + ownerB))
                .andReturn();
        assertStatus(delete, 403);
        assertProblemCode(delete, "AUTH_FORBIDDEN");
        assertStored(orgA, created.id(), value, null);
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
        String slug = "sec-" + UUID.randomUUID().toString().substring(0, 8);
        MvcResult result = mockMvc.perform(post("/api/v1/orgs")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "Secrets " + slug, "slug", slug))))
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

    private Created createSecret(String token, UUID orgId, String name, String plaintext) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/orgs/{orgId}/secrets", orgId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "name", name,
                                "purpose", "AGENT",
                                "plaintext", plaintext))))
                .andReturn();
        assertStatus(result, 201);
        assertNoSecretMaterial(result, plaintext);
        JsonNode body = read(result);
        if (!body.path("rotatedAt").isNull() && !body.path("rotatedAt").asText("").isBlank()) {
            throw new AssertionError("new secret was already marked rotated");
        }
        return new Created(UUID.fromString(body.path("id").asText()));
    }

    private JsonNode listSecrets(String token, UUID orgId) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/orgs/{orgId}/secrets", orgId)
                        .header("Authorization", "Bearer " + token))
                .andReturn();
        assertStatus(result, 200);
        return read(result);
    }

    private void assertStored(UUID orgId, UUID secretId, String expected, String previous) {
        String stored = RlsContext.callWithOrg(orgId, () -> secretService.decryptForOrg(orgId, secretId));
        boolean matches = expected.equals(stored);
        boolean stillPrevious = previous != null && previous.equals(stored);
        if (!matches || stillPrevious) {
            throw new AssertionError("stored secret did not match the rotated value");
        }
    }

    private void assertMissing(UUID orgId, UUID secretId) {
        try {
            RlsContext.callWithOrg(orgId, () -> secretService.decryptForOrg(orgId, secretId));
            throw new AssertionError("deleted secret was still stored");
        } catch (ApiException ex) {
            if (ex.getStatus() != HttpStatus.NOT_FOUND) {
                throw new AssertionError("expected the deleted secret to be missing");
            }
        }
    }

    private static JsonNode findListed(JsonNode listed, UUID secretId) {
        if (!listed.isArray()) {
            throw new AssertionError("secret list was not an array");
        }
        for (JsonNode item : listed) {
            if (secretId.toString().equals(item.path("id").asText())) {
                return item;
            }
        }
        return null;
    }

    private void assertNoSecretMaterial(MvcResult result, String... secrets) throws Exception {
        assertNoSecretMaterial(result.getResponse().getContentAsString(), secrets);
    }

    private static void assertNoSecretMaterial(String body, String... secrets) {
        if (body.contains("\"plaintext\"") || body.contains("\"ciphertext\"")) {
            throw new AssertionError("response included secret fields");
        }
        for (String secret : secrets) {
            if (secret != null && !secret.isBlank() && body.contains(secret)) {
                throw new AssertionError("response included a secret value");
            }
        }
    }

    private void assertProblemCode(MvcResult result, String code) throws Exception {
        if (!code.equals(read(result).path("code").asText())) {
            throw new AssertionError("expected error code " + code);
        }
    }

    private static void assertStatus(MvcResult result, int expected) {
        int actual = result.getResponse().getStatus();
        if (actual != expected) {
            throw new AssertionError("expected HTTP " + expected + " but got " + actual);
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

    private static String secretValue() {
        return UUID.randomUUID() + "." + UUID.randomUUID();
    }

    private record Created(UUID id) {
    }
}
