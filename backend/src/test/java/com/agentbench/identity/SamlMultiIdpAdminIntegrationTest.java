package com.agentbench.identity;

import com.agentbench.identity.auth.SamlRegistrationFactory;
import com.agentbench.support.PostgresTestSupport;
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
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Multi-IdP SAML admin CRUD + authz + login against WireMock IdP metadata.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class SamlMultiIdpAdminIntegrationTest {

    private static final String SP_ENTITY_ID = "http://localhost:8080/saml2/metadata";
    private static final String ACS_URL = "http://localhost:8080/api/v1/auth/saml/acs";
    private static final String IDP_ENTITY_ID = "http://idp-multi.example.test/metadata";

    private static final SamlFixtureSupport.IdpKeys IDP = SamlFixtureSupport.generateIdp(IDP_ENTITY_ID);

    @Container
    static PostgreSQLContainer<?> postgres = PostgresTestSupport.postgres();

    @RegisterExtension
    static WireMockExtension idp = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        PostgresTestSupport.registerRlsAwareDatasource(registry, postgres);
        // SP ready without env IdP metadata — DB IdPs are the source of truth in these tests.
        registry.add("agentbench.saml.enabled", () -> "true");
        registry.add("agentbench.saml.entity-id", () -> SP_ENTITY_ID);
        registry.add("agentbench.saml.acs-url", () -> ACS_URL);
        registry.add("agentbench.saml.success-redirect-url", () -> "http://localhost:3000/");
        registry.add("agentbench.saml.registration-id", () -> "agentbench");
        registry.add("agentbench.saml.metadata-url", () -> "");
        registry.add("agentbench.saml.metadata-xml", () -> "");
    }

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    SamlRegistrationFactory registrationFactory;

    @BeforeEach
    void reset() {
        registrationFactory.evict();
        idp.resetAll();
        String metadata = IDP.metadataXml(idp.baseUrl() + "/idp/sso");
        idp.stubFor(com.github.tomakehurst.wiremock.client.WireMock.get(urlEqualTo("/idp/metadata"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/samlmetadata+xml")
                        .withBody(metadata)));
    }

    @Test
    void ownerCanCrudIdpAndLoginUsesDbRegistration() throws Exception {
        String ownerToken = register("owner-" + UUID.randomUUID() + "@example.com", "Owner");
        UUID orgId = createOrg(ownerToken, "SAML Org " + UUID.randomUUID(), "saml-" + UUID.randomUUID().toString().substring(0, 8));
        String registrationId = "okta-" + UUID.randomUUID().toString().substring(0, 8);

        MvcResult created = mockMvc.perform(post("/api/v1/orgs/{orgId}/saml/idps", orgId)
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "registrationId":"%s",
                                  "displayName":"Okta Corp",
                                  "metadataUrl":"%s",
                                  "attrEmail":"email",
                                  "attrDisplayName":"displayName",
                                  "enabled":true
                                }
                                """.formatted(registrationId, idp.baseUrl() + "/idp/metadata")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.registrationId").value(registrationId))
                .andExpect(jsonPath("$.displayName").value("Okta Corp"))
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.hasMetadataXml").value(false))
                .andReturn();
        UUID idpId = UUID.fromString(objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asText());

        mockMvc.perform(get("/api/v1/orgs/{orgId}/saml/idps", orgId)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(idpId.toString()))
                .andExpect(jsonPath("$[0].registrationId").value(registrationId));

        mockMvc.perform(get("/api/v1/auth/providers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.providers[?(@.id=='%s')].type".formatted(registrationId)).value("saml"))
                .andExpect(jsonPath("$.providers[?(@.id=='%s')].authorizePath".formatted(registrationId))
                        .value("/api/v1/auth/saml/login/" + registrationId))
                .andExpect(jsonPath("$.providers[?(@.id=='%s')].displayName".formatted(registrationId))
                        .value("Okta Corp"));

        LoginStart start = startLogin(registrationId);
        idp.verify(getRequestedFor(urlEqualTo("/idp/metadata")));

        String email = "db-idp-" + UUID.randomUUID() + "@example.com";
        String samlResponse = SamlFixtureSupport.signedResponseBase64(
                IDP, SP_ENTITY_ID, ACS_URL, start.authnRequestId(), email, email, "DB IdP User");
        mockMvc.perform(post("/api/v1/auth/saml/token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"samlResponse":"%s","relayState":"%s"}
                                """.formatted(samlResponse, start.relayState())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isString());

        mockMvc.perform(put("/api/v1/orgs/{orgId}/saml/idps/{idpId}", orgId, idpId)
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "displayName":"Okta Corp Disabled",
                                  "metadataUrl":"%s",
                                  "attrEmail":"email",
                                  "attrDisplayName":"displayName",
                                  "enabled":false
                                }
                                """.formatted(idp.baseUrl() + "/idp/metadata")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false))
                .andExpect(jsonPath("$.displayName").value("Okta Corp Disabled"));

        mockMvc.perform(get("/api/v1/auth/providers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.providers[?(@.id=='%s')]".formatted(registrationId)).isEmpty());

        mockMvc.perform(delete("/api/v1/orgs/{orgId}/saml/idps/{idpId}", orgId, idpId)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/orgs/{orgId}/saml/idps", orgId)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    void viewerAndDeveloperCannotManageIdps() throws Exception {
        String ownerToken = register("owner2-" + UUID.randomUUID() + "@example.com", "Owner");
        String viewerToken = register("viewer-" + UUID.randomUUID() + "@example.com", "Viewer");
        String developerToken = register("dev-" + UUID.randomUUID() + "@example.com", "Dev");
        UUID orgId = createOrg(ownerToken, "RBAC SAML " + UUID.randomUUID(), "rbac-s-" + UUID.randomUUID().toString().substring(0, 8));

        mockMvc.perform(post("/api/v1/orgs/{orgId}/members", orgId)
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","role":"VIEWER"}
                                """.formatted(meEmail(viewerToken))))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/v1/orgs/{orgId}/members", orgId)
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","role":"DEVELOPER"}
                                """.formatted(meEmail(developerToken))))
                .andExpect(status().isCreated());

        String body = """
                {
                  "registrationId":"deny-%s",
                  "displayName":"Denied",
                  "metadataUrl":"%s",
                  "enabled":true
                }
                """.formatted(UUID.randomUUID().toString().substring(0, 8), idp.baseUrl() + "/idp/metadata");

        mockMvc.perform(post("/api/v1/orgs/{orgId}/saml/idps", orgId)
                        .header("Authorization", "Bearer " + viewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/v1/orgs/{orgId}/saml/idps", orgId)
                        .header("Authorization", "Bearer " + developerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/v1/orgs/{orgId}/saml/idps", orgId)
                        .header("Authorization", "Bearer " + viewerToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminCanCreateIdpWithInlineMetadataXml() throws Exception {
        String adminOwner = register("admin-" + UUID.randomUUID() + "@example.com", "Admin");
        UUID orgId = createOrg(adminOwner, "XML Org " + UUID.randomUUID(), "xml-" + UUID.randomUUID().toString().substring(0, 8));
        String registrationId = "adfs-" + UUID.randomUUID().toString().substring(0, 8);
        String metadataXml = IDP.metadataXml(idp.baseUrl() + "/idp/sso");
        String body = objectMapper.writeValueAsString(Map.of(
                "registrationId", registrationId,
                "displayName", "ADFS",
                "metadataXml", metadataXml,
                "enabled", true
        ));

        mockMvc.perform(post("/api/v1/orgs/{orgId}/saml/idps", orgId)
                        .header("Authorization", "Bearer " + adminOwner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.hasMetadataXml").value(true));

        LoginStart start = startLogin(registrationId);
        assertTrue(start.relayState() != null && !start.relayState().isBlank());
        assertEquals(true, start.authnRequestId().startsWith("_"));
    }

    private LoginStart startLogin(String registrationId) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/auth/saml/login/{registrationId}", registrationId))
                .andExpect(status().isFound())
                .andReturn();
        String location = result.getResponse().getHeader("Location");
        Map<String, String> query = queryParams(URI.create(location));
        String relayState = query.get("RelayState");
        String authnRequestId = extractAuthnRequestId(query.get("SAMLRequest"));
        return new LoginStart(relayState, authnRequestId);
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

    private String meEmail(String token) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode me = objectMapper.readTree(result.getResponse().getContentAsString());
        return me.get("email").asText();
    }

    private static String extractAuthnRequestId(String samlRequest) throws Exception {
        byte[] decoded = java.util.Base64.getDecoder().decode(samlRequest);
        java.util.zip.Inflater inflater = new java.util.zip.Inflater(true);
        inflater.setInput(decoded);
        byte[] result = new byte[8192];
        int len = inflater.inflate(result);
        inflater.end();
        String xml = new String(result, 0, len, StandardCharsets.UTF_8);
        int idIdx = xml.indexOf("ID=\"");
        assertTrue(idIdx >= 0, "AuthnRequest missing ID");
        int start = idIdx + 4;
        int end = xml.indexOf('"', start);
        return xml.substring(start, end);
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

    private record LoginStart(String relayState, String authnRequestId) {
    }
}
