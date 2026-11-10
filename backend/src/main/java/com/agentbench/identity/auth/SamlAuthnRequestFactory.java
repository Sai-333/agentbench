package com.agentbench.identity.auth;

import org.springframework.security.saml2.provider.service.registration.RelyingPartyRegistration;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;

/**
 * Builds SP-initiated SAML 2.0 AuthnRequest redirect URLs (HTTP-Redirect binding, unsigned).
 */
@Component
public class SamlAuthnRequestFactory {

    public URI buildRedirect(RelyingPartyRegistration registration, String authnRequestId, String relayState) {
        String destination = registration.getAssertingPartyDetails().getSingleSignOnServiceLocation();
        String xml = authnRequestXml(
                authnRequestId,
                Instant.now(),
                destination,
                registration.getAssertionConsumerServiceLocation(),
                registration.getEntityId()
        );
        String samlRequest = deflateBase64(xml);
        return UriComponentsBuilder
                .fromUriString(destination)
                .queryParam("SAMLRequest", samlRequest)
                .queryParam("RelayState", relayState)
                .encode()
                .build()
                .toUri();
    }

    static String authnRequestXml(
            String id,
            Instant issueInstant,
            String destination,
            String acsUrl,
            String spEntityId) {
        return """
                <samlp:AuthnRequest xmlns:samlp="urn:oasis:names:tc:SAML:2.0:protocol"\
                 xmlns:saml="urn:oasis:names:tc:SAML:2.0:assertion"\
                 ID="%s" Version="2.0" IssueInstant="%s"\
                 Destination="%s"\
                 AssertionConsumerServiceURL="%s"\
                 ProtocolBinding="urn:oasis:names:tc:SAML:2.0:bindings:HTTP-POST">\
                <saml:Issuer>%s</saml:Issuer>\
                <samlp:NameIDPolicy Format="urn:oasis:names:tc:SAML:1.1:nameid-format:emailAddress" AllowCreate="true"/>\
                </samlp:AuthnRequest>
                """.formatted(
                xmlEscape(id),
                issueInstant.toString(),
                xmlEscape(destination),
                xmlEscape(acsUrl),
                xmlEscape(spEntityId)
        ).replaceAll(">\\s+<", "><").trim();
    }

    static String deflateBase64(String xml) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            Deflater deflater = new Deflater(Deflater.DEFLATED, true);
            try (DeflaterOutputStream deflaterStream = new DeflaterOutputStream(bytes, deflater)) {
                deflaterStream.write(xml.getBytes(StandardCharsets.UTF_8));
            }
            return Base64.getEncoder().encodeToString(bytes.toByteArray());
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to encode SAML AuthnRequest", ex);
        }
    }

    private static String xmlEscape(String value) {
        return value
                .replace("&", "&amp;")
                .replace("\"", "&quot;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }
}
