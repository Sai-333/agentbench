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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * SAML 2.0 SP login against WireMock IdP metadata + locally signed assertions (no live IdP).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class SamlAuthIntegrationTest {

    private static final String SP_ENTITY_ID = "http://localhost:8080/saml2/metadata";
    private static final String ACS_URL = "http://localhost:8080/api/v1/auth/saml/acs";
    private static final String IDP_ENTITY_ID = "http://idp.example.test/metadata";

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
        registry.add("agentbench.saml.enabled", () -> "true");
        registry.add("agentbench.saml.entity-id", () -> SP_ENTITY_ID);
        registry.add("agentbench.saml.acs-url", () -> ACS_URL);
        registry.add("agentbench.saml.success-redirect-url", () -> "http://localhost:3000/");
        registry.add("agentbench.saml.display-name", () -> "Enterprise SSO");
        registry.add("agentbench.saml.attribute-mapping.email", () -> "email");
        registry.add("agentbench.saml.attribute-mapping.display-name", () -> "displayName");
        registry.add("agentbench.saml.metadata-url", () -> idp.baseUrl() + "/idp/metadata");
    }

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    SamlRegistrationFactory registrationFactory;

    @BeforeEach
    void stubMetadata() {
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
    void providersListsSamlBesideLocal() throws Exception {
        mockMvc.perform(get("/api/v1/auth/providers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.providers[?(@.id=='local')]").exists())
                .andExpect(jsonPath("$.providers[?(@.id=='saml')].type").value("saml"))
                .andExpect(jsonPath("$.providers[?(@.id=='saml')].authorizePath")
                        .value("/api/v1/auth/saml/login"))
                .andExpect(jsonPath("$.providers[?(@.id=='saml')].displayName")
                        .value("Enterprise SSO"));
    }

    @Test
    void loginRedirectsToIdpWithSamlRequest() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/auth/saml/login"))
                .andExpect(status().isFound())
                .andReturn();
        String location = result.getResponse().getHeader("Location");
        assertTrue(location.startsWith(idp.baseUrl() + "/idp/sso"));
        Map<String, String> query = queryParams(URI.create(location));
        assertTrue(query.containsKey("SAMLRequest"));
        assertTrue(query.containsKey("RelayState"));
        idp.verify(getRequestedFor(urlEqualTo("/idp/metadata")));
    }

    @Test
    void tokenEndpointIssuesJwtAndLinksUser() throws Exception {
        String email = "saml-user-" + UUID.randomUUID() + "@example.com";
        LoginStart start = startLogin();
        String samlResponse = SamlFixtureSupport.signedResponseBase64(
                IDP, SP_ENTITY_ID, ACS_URL, start.authnRequestId(), email, email, "SAML User");

        MvcResult tokenResult = mockMvc.perform(post("/api/v1/auth/saml/token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"samlResponse":"%s","relayState":"%s"}
                                """.formatted(samlResponse, start.relayState())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isString())
                .andExpect(jsonPath("$.refreshToken").isString())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andReturn();

        JsonNode tokens = objectMapper.readTree(tokenResult.getResponse().getContentAsString());
        String access = tokens.get("accessToken").asText();
        mockMvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + access))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(email))
                .andExpect(jsonPath("$.displayName").value("SAML User"));

        // Second login with same NameID reuses the linked user
        LoginStart start2 = startLogin();
        String samlResponse2 = SamlFixtureSupport.signedResponseBase64(
                IDP, SP_ENTITY_ID, ACS_URL, start2.authnRequestId(), email, email, "SAML User");
        MvcResult second = mockMvc.perform(post("/api/v1/auth/saml/token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"samlResponse":"%s","relayState":"%s"}
                                """.formatted(samlResponse2, start2.relayState())))
                .andExpect(status().isOk())
                .andReturn();
        String access2 = objectMapper.readTree(second.getResponse().getContentAsString()).get("accessToken").asText();
        MvcResult me1 = mockMvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + access)).andReturn();
        MvcResult me2 = mockMvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + access2)).andReturn();
        assertEquals(
                objectMapper.readTree(me1.getResponse().getContentAsString()).get("id").asText(),
                objectMapper.readTree(me2.getResponse().getContentAsString()).get("id").asText()
        );
    }

    @Test
    void acsRedirectsWithTokenFragment() throws Exception {
        String email = "saml-acs-" + UUID.randomUUID() + "@example.com";
        LoginStart start = startLogin();
        String samlResponse = SamlFixtureSupport.signedResponseBase64(
                IDP, SP_ENTITY_ID, ACS_URL, start.authnRequestId(), email, email, "ACS User");

        MvcResult result = mockMvc.perform(post("/api/v1/auth/saml/acs")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("SAMLResponse", samlResponse)
                        .param("RelayState", start.relayState()))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", org.hamcrest.Matchers.startsWith("http://localhost:3000/#")))
                .andReturn();
        String location = result.getResponse().getHeader("Location");
        assertTrue(location.contains("access_token="));
        assertTrue(location.contains("refresh_token="));
        assertFalse(location.contains("BEGIN CERTIFICATE"));
    }

    @Test
    void rejectsReuseOfRelayState() throws Exception {
        String email = "saml-reuse-" + UUID.randomUUID() + "@example.com";
        LoginStart start = startLogin();
        String samlResponse = SamlFixtureSupport.signedResponseBase64(
                IDP, SP_ENTITY_ID, ACS_URL, start.authnRequestId(), email, email, "Reuse");
        mockMvc.perform(post("/api/v1/auth/saml/token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"samlResponse":"%s","relayState":"%s"}
                                """.formatted(samlResponse, start.relayState())))
                .andExpect(status().isOk());

        String samlResponse2 = SamlFixtureSupport.signedResponseBase64(
                IDP, SP_ENTITY_ID, ACS_URL, start.authnRequestId(), email, email, "Reuse");
        mockMvc.perform(post("/api/v1/auth/saml/token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"samlResponse":"%s","relayState":"%s"}
                                """.formatted(samlResponse2, start.relayState())))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void linksSamlIdentityToExistingPasswordUser() throws Exception {
        String email = "saml-link-" + UUID.randomUUID() + "@example.com";
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"password123","displayName":"Local First"}
                                """.formatted(email)))
                .andExpect(status().isCreated());

        LoginStart start = startLogin();
        String samlResponse = SamlFixtureSupport.signedResponseBase64(
                IDP, SP_ENTITY_ID, ACS_URL, start.authnRequestId(), email, email, "Local First");
        MvcResult saml = mockMvc.perform(post("/api/v1/auth/saml/token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"samlResponse":"%s","relayState":"%s"}
                                """.formatted(samlResponse, start.relayState())))
                .andExpect(status().isOk())
                .andReturn();
        String access = objectMapper.readTree(saml.getResponse().getContentAsString()).get("accessToken").asText();
        mockMvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + access))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(email))
                .andExpect(jsonPath("$.displayName").value("Local First"));

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"password123","provider":"local"}
                                """.formatted(email)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isString());
    }

    @Test
    void spMetadataExportsEntityDescriptor() throws Exception {
        mockMvc.perform(get("/api/v1/auth/saml/metadata"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", org.hamcrest.Matchers.containsString("xml")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                        .string(org.hamcrest.Matchers.containsString(SP_ENTITY_ID)))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                        .string(org.hamcrest.Matchers.containsString(ACS_URL)));
    }

    private LoginStart startLogin() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/auth/saml/login"))
                .andExpect(status().isFound())
                .andReturn();
        String location = result.getResponse().getHeader("Location");
        Map<String, String> query = queryParams(URI.create(location));
        String relayState = query.get("RelayState");
        // AuthnRequest ID is stored as nonce; recover by decoding is hard — re-read via a second
        // login is not needed: signedResponse uses InResponseTo from start. We extract ID by
        // inflating SAMLRequest is complex; instead peek via completing requires matching ID.
        // Store path: nonce=authnRequestId. Tests recover it by parsing deflated SAMLRequest.
        String authnRequestId = extractAuthnRequestId(query.get("SAMLRequest"));
        return new LoginStart(relayState, authnRequestId);
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
