package com.agentbench.identity;

import com.agentbench.identity.auth.SamlAuthnRequestFactory;
import com.agentbench.identity.auth.SamlProperties;
import com.agentbench.identity.auth.SamlSpSigningCredentials;
import org.junit.jupiter.api.Test;
import org.springframework.security.saml2.core.Saml2X509Credential;
import org.springframework.security.saml2.provider.service.registration.RelyingPartyRegistration;
import org.springframework.security.saml2.provider.service.registration.RelyingPartyRegistrations;
import org.springframework.security.saml2.provider.service.registration.Saml2MessageBinding;
import org.springframework.web.util.UriUtils;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.Signature;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.zip.Inflater;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SamlAuthnRequestFactoryTest {

    @Test
    void buildsRedirectWithDeflatedAuthnRequestUnsignedByDefault() throws Exception {
        RelyingPartyRegistration registration = registration(false);
        SamlAuthnRequestFactory factory = new SamlAuthnRequestFactory(inactiveSigning());
        URI redirect = factory.buildRedirect(registration, "_req-1", "relay-abc");
        assertTrue(redirect.toString().startsWith("https://idp.test/sso"));
        Map<String, String> query = queryParams(redirect);
        assertTrue(query.containsKey("SAMLRequest"));
        assertTrue(query.containsKey("RelayState"));
        assertFalse(query.containsKey("Signature"));
        assertFalse(query.containsKey("SigAlg"));

        String xml = inflate(query.get("SAMLRequest"));
        assertTrue(xml.contains("ID=\"_req-1\""));
        assertTrue(xml.contains("http://sp.test/metadata"));
        assertTrue(xml.contains("http://sp.test/acs"));
        assertFalse(xml.contains("Signature"));
    }

    @Test
    void buildsRedirectWithQuerySignatureWhenSpSigningEnabled() throws Exception {
        SamlFixtureSupport.SpKeys sp = SamlFixtureSupport.generateSp("sp.test");
        SamlProperties properties = new SamlProperties();
        properties.getSigning().setEnabled(true);
        properties.getSigning().setPrivateKey(sp.privateKeyPem());
        properties.getSigning().setCertificate(sp.certificatePem());
        SamlSpSigningCredentials credentials = new SamlSpSigningCredentials(properties);
        assertTrue(credentials.isActive());

        RelyingPartyRegistration registration = registration(true);
        SamlAuthnRequestFactory factory = new SamlAuthnRequestFactory(credentials);
        URI redirect = factory.buildRedirect(registration, "_req-signed", "relay-signed");

        Map<String, String> query = queryParams(redirect);
        assertTrue(query.containsKey("SAMLRequest"));
        assertTrue(query.containsKey("RelayState"));
        assertTrue(query.containsKey("SigAlg"));
        assertTrue(query.containsKey("Signature"));
        assertTrue(query.get("SigAlg").contains("rsa-sha256"));

        // AuthnRequest XML itself stays unsigned for Redirect binding.
        String xml = inflate(query.get("SAMLRequest"));
        assertTrue(xml.contains("ID=\"_req-signed\""));
        assertFalse(xml.contains("<ds:Signature") || xml.contains("SignatureValue"));

        String ordered = "SAMLRequest=" + SamlAuthnRequestFactory.uriEncode(query.get("SAMLRequest"))
                + "&RelayState=" + SamlAuthnRequestFactory.uriEncode(query.get("RelayState"))
                + "&SigAlg=" + SamlAuthnRequestFactory.uriEncode(query.get("SigAlg"));
        Signature verifier = Signature.getInstance("SHA256withRSA");
        verifier.initVerify(sp.certificate().getPublicKey());
        verifier.update(ordered.getBytes(StandardCharsets.UTF_8));
        assertTrue(verifier.verify(Base64.getDecoder().decode(query.get("Signature"))),
                "Redirect Signature must verify with SP cert");
    }

    @Test
    void signedRedirectHelperProducesVerifiableSignature() throws Exception {
        SamlFixtureSupport.SpKeys sp = SamlFixtureSupport.generateSp("helper.sp");
        Saml2X509Credential credential = Saml2X509Credential.signing(sp.keyPair().getPrivate(), sp.certificate());
        URI redirect = SamlAuthnRequestFactory.signedRedirect(
                "https://idp.test/sso",
                Base64.getEncoder().encodeToString("deflated".getBytes(StandardCharsets.UTF_8)),
                "relay",
                credential
        );
        Map<String, String> query = queryParams(redirect);
        assertTrue(query.containsKey("Signature"));
        assertTrue(query.get("SigAlg").equals(SamlAuthnRequestFactory.RSA_SHA256));
    }

    private static RelyingPartyRegistration registration(boolean wantSigned) {
        SamlFixtureSupport.IdpKeys idp = SamlFixtureSupport.generateIdp("http://idp.test/metadata");
        String metadata = idp.metadataXml("https://idp.test/sso", wantSigned);
        return RelyingPartyRegistrations
                .fromMetadata(new ByteArrayInputStream(metadata.getBytes(StandardCharsets.UTF_8)))
                .registrationId("agentbench")
                .entityId("http://sp.test/metadata")
                .assertionConsumerServiceLocation("http://sp.test/acs")
                .assertionConsumerServiceBinding(Saml2MessageBinding.POST)
                .assertingPartyDetails(party -> party.wantAuthnRequestsSigned(wantSigned))
                .build();
    }

    private static SamlSpSigningCredentials inactiveSigning() {
        SamlProperties properties = new SamlProperties();
        properties.getSigning().setEnabled(false);
        return new SamlSpSigningCredentials(properties);
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

    private static Map<String, String> queryParams(URI uri) {
        String raw = uri.getRawQuery();
        if (raw == null || raw.isBlank()) {
            return Map.of();
        }
        // UriUtils.decode keeps '+' (Base64) — unlike URLDecoder which turns '+' into space.
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
