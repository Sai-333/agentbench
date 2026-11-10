package com.agentbench.identity;

import com.agentbench.identity.auth.SamlProperties;
import com.agentbench.identity.auth.SamlResponseProcessor;
import com.agentbench.identity.auth.SamlUserProfile;
import org.junit.jupiter.api.Test;
import org.springframework.security.saml2.provider.service.registration.RelyingPartyRegistration;
import org.springframework.security.saml2.provider.service.registration.RelyingPartyRegistrations;
import org.springframework.security.saml2.provider.service.registration.Saml2MessageBinding;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * OpenSAML assertion validation without Spring Boot / Docker / live IdP.
 */
class SamlResponseProcessorTest {

    @Test
    void validatesSignedAssertionAndMapsAttributes() {
        SamlFixtureSupport.IdpKeys idp = SamlFixtureSupport.generateIdp("http://idp.test/metadata");
        String spEntityId = "http://sp.test/metadata";
        String acs = "http://sp.test/acs";
        String authnRequestId = "_req-validate-1";

        RelyingPartyRegistration registration = RelyingPartyRegistrations
                .fromMetadata(new ByteArrayInputStream(idp.metadataXml("https://idp.test/sso").getBytes(StandardCharsets.UTF_8)))
                .registrationId("agentbench")
                .entityId(spEntityId)
                .assertionConsumerServiceLocation(acs)
                .assertionConsumerServiceBinding(Saml2MessageBinding.POST)
                .assertingPartyDetails(party -> party.wantAuthnRequestsSigned(false))
                .build();

        String response = SamlFixtureSupport.signedResponseBase64(
                idp, spEntityId, acs, authnRequestId,
                "user@example.com", "user@example.com", "Example User");

        SamlProperties properties = new SamlProperties();
        properties.getAttributeMapping().setEmail("email");
        properties.getAttributeMapping().setDisplayName("displayName");
        SamlResponseProcessor processor = new SamlResponseProcessor(properties);

        SamlUserProfile profile = processor.process(registration, response, authnRequestId);
        assertEquals("user@example.com", profile.subject());
        assertEquals("user@example.com", profile.email());
        assertEquals("Example User", profile.displayName());
    }

    @Test
    void rejectsTamperedResponse() {
        SamlFixtureSupport.IdpKeys idp = SamlFixtureSupport.generateIdp("http://idp.test/metadata");
        String spEntityId = "http://sp.test/metadata";
        String acs = "http://sp.test/acs";
        RelyingPartyRegistration registration = RelyingPartyRegistrations
                .fromMetadata(new ByteArrayInputStream(idp.metadataXml("https://idp.test/sso").getBytes(StandardCharsets.UTF_8)))
                .registrationId("agentbench")
                .entityId(spEntityId)
                .assertionConsumerServiceLocation(acs)
                .assertionConsumerServiceBinding(Saml2MessageBinding.POST)
                .assertingPartyDetails(party -> party.wantAuthnRequestsSigned(false))
                .build();

        String response = SamlFixtureSupport.signedResponseBase64(
                idp, spEntityId, acs, "_req-2",
                "user@example.com", "user@example.com", "Example User");
        // Corrupt payload after signing
        String tampered = response.substring(0, response.length() - 8) + "AAAAAAAA";

        SamlResponseProcessor processor = new SamlResponseProcessor(new SamlProperties());
        assertThrows(Exception.class, () -> processor.process(registration, tampered, "_req-2"));
    }
}
