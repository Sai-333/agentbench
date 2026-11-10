package com.agentbench.identity;

import com.agentbench.identity.auth.SamlRegistrationFactory;
import com.agentbench.identity.auth.SamlSpSigningCredentials;
import com.agentbench.support.PostgresTestSupport;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.Signature;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.web.util.UriUtils;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Proves SP signing: AuthnRequest redirect carries SigAlg/Signature when enabled,
 * and SP metadata publishes the signing certificate.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class SamlSpSigningIntegrationTest {

    private static final String SP_ENTITY_ID = "http://localhost:8080/saml2/metadata";
    private static final String ACS_URL = "http://localhost:8080/api/v1/auth/saml/acs";
    private static final String IDP_ENTITY_ID = "http://idp.signing.test/metadata";

    private static final SamlFixtureSupport.IdpKeys IDP = SamlFixtureSupport.generateIdp(IDP_ENTITY_ID);
    private static final SamlFixtureSupport.SpKeys SP = SamlFixtureSupport.generateSp(SP_ENTITY_ID);
    private static final Path KEY_PATH;
    private static final Path CERT_PATH;

    static {
        try {
            Path dir = Files.createTempDirectory("saml-sp-signing");
            KEY_PATH = dir.resolve("sp-key.pem");
            CERT_PATH = dir.resolve("sp-cert.pem");
            Files.writeString(KEY_PATH, SP.privateKeyPem());
            Files.writeString(CERT_PATH, SP.certificatePem());
        } catch (Exception ex) {
            throw new ExceptionInInitializerError(ex);
        }
    }

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
        registry.add("agentbench.saml.metadata-url", () -> idp.baseUrl() + "/idp/metadata");
        registry.add("agentbench.saml.signing.enabled", () -> "true");
        registry.add("agentbench.saml.signing.private-key-path", KEY_PATH::toString);
        registry.add("agentbench.saml.signing.certificate-path", CERT_PATH::toString);
    }

    @Autowired
    MockMvc mockMvc;

    @Autowired
    SamlRegistrationFactory registrationFactory;

    @Autowired
    SamlSpSigningCredentials signingCredentials;

    @BeforeEach
    void stubMetadata() {
        registrationFactory.evict();
        signingCredentials.evict();
        idp.resetAll();
        String metadata = IDP.metadataXml(idp.baseUrl() + "/idp/sso", true);
        idp.stubFor(com.github.tomakehurst.wiremock.client.WireMock.get(urlEqualTo("/idp/metadata"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/samlmetadata+xml")
                        .withBody(metadata)));
    }

    @Test
    void loginRedirectIncludesVerifiableSignature() throws Exception {
        assertTrue(signingCredentials.isActive());

        MvcResult result = mockMvc.perform(get("/api/v1/auth/saml/login"))
                .andExpect(status().isFound())
                .andReturn();
        String location = result.getResponse().getHeader("Location");
        assertTrue(location.startsWith(idp.baseUrl() + "/idp/sso"));

        Map<String, String> query = queryParams(URI.create(location));
        assertTrue(query.containsKey("SAMLRequest"));
        assertTrue(query.containsKey("RelayState"));
        assertTrue(query.containsKey("SigAlg"), "SigAlg must be present when signing enabled");
        assertTrue(query.containsKey("Signature"), "Signature must be present when signing enabled");
        assertTrue(query.get("SigAlg").contains("rsa-sha256"));

        String ordered = "SAMLRequest=" + encode(query.get("SAMLRequest"))
                + "&RelayState=" + encode(query.get("RelayState"))
                + "&SigAlg=" + encode(query.get("SigAlg"));
        Signature verifier = Signature.getInstance("SHA256withRSA");
        verifier.initVerify(SP.certificate().getPublicKey());
        verifier.update(ordered.getBytes(StandardCharsets.UTF_8));
        assertTrue(verifier.verify(Base64.getDecoder().decode(query.get("Signature"))));
    }

    @Test
    void spMetadataPublishesSigningCertAndAuthnRequestsSigned() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/auth/saml/metadata"))
                .andExpect(status().isOk())
                .andReturn();
        String xml = result.getResponse().getContentAsString();
        assertTrue(xml.contains("AuthnRequestsSigned=\"true\""));
        assertTrue(xml.contains("<md:KeyDescriptor use=\"signing\">"));
        assertTrue(xml.contains("<ds:X509Certificate>"));
        String expectedCert = Base64.getEncoder().encodeToString(SP.certificate().getEncoded());
        assertTrue(xml.contains(expectedCert));
        assertFalse(xml.contains("AuthnRequestsSigned=\"false\""));
    }

    private static String encode(String value) {
        return org.springframework.web.util.UriUtils.encode(value, StandardCharsets.ISO_8859_1);
    }

    private static Map<String, String> queryParams(URI uri) {
        String raw = uri.getRawQuery();
        if (raw == null || raw.isBlank()) {
            return Map.of();
        }
        return Arrays.stream(raw.split("&"))
                .map(part -> part.split("=", 2))
                .collect(Collectors.toMap(
                        parts -> UriUtils.decode(parts[0], StandardCharsets.UTF_8),
                        parts -> parts.length > 1 ? UriUtils.decode(parts[1], StandardCharsets.UTF_8) : "",
                        (a, b) -> b,
                        LinkedHashMap::new
                ));
    }
}
