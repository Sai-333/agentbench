package com.agentbench.identity;

import com.agentbench.identity.auth.SamlAuthnRequestFactory;
import org.junit.jupiter.api.Test;
import org.springframework.security.saml2.provider.service.registration.RelyingPartyRegistration;
import org.springframework.security.saml2.provider.service.registration.RelyingPartyRegistrations;
import org.springframework.security.saml2.provider.service.registration.Saml2MessageBinding;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.zip.Inflater;

import static org.junit.jupiter.api.Assertions.assertTrue;

class SamlAuthnRequestFactoryTest {

    @Test
    void buildsRedirectWithDeflatedAuthnRequest() throws Exception {
        SamlFixtureSupport.IdpKeys idp = SamlFixtureSupport.generateIdp("http://idp.test/metadata");
        String metadata = idp.metadataXml("https://idp.test/sso");
        RelyingPartyRegistration registration = RelyingPartyRegistrations
                .fromMetadata(new ByteArrayInputStream(metadata.getBytes(StandardCharsets.UTF_8)))
                .registrationId("agentbench")
                .entityId("http://sp.test/metadata")
                .assertionConsumerServiceLocation("http://sp.test/acs")
                .assertionConsumerServiceBinding(Saml2MessageBinding.POST)
                .assertingPartyDetails(party -> party.wantAuthnRequestsSigned(false))
                .build();

        SamlAuthnRequestFactory factory = new SamlAuthnRequestFactory();
        URI redirect = factory.buildRedirect(registration, "_req-1", "relay-abc");
        assertTrue(redirect.toString().startsWith("https://idp.test/sso"));
        assertTrue(redirect.getRawQuery().contains("SAMLRequest="));
        assertTrue(redirect.getRawQuery().contains("RelayState=relay-abc"));

        String encoded = java.net.URLDecoder.decode(
                redirect.getRawQuery().split("SAMLRequest=")[1].split("&")[0],
                StandardCharsets.UTF_8).replace(' ', '+');
        String xml = inflate(encoded);
        assertTrue(xml.contains("ID=\"_req-1\""));
        assertTrue(xml.contains("http://sp.test/metadata"));
        assertTrue(xml.contains("http://sp.test/acs"));
    }

    private static String inflate(String base64) throws Exception {
        byte[] decoded = Base64.getDecoder().decode(base64);
        Inflater inflater = new Inflater(true);
        inflater.setInput(decoded);
        byte[] out = new byte[8192];
        int len = inflater.inflate(out);
        inflater.end();
        return new String(out, 0, len, StandardCharsets.UTF_8);
    }
}
