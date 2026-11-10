package com.agentbench.identity.auth;

import org.springframework.security.saml2.core.Saml2X509Credential;
import org.springframework.security.saml2.provider.service.registration.RelyingPartyRegistration;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.web.util.UriUtils;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.Signature;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;

/**
 * Builds SP-initiated SAML 2.0 AuthnRequest redirect URLs (HTTP-Redirect binding).
 * When platform SP signing credentials are configured, adds SigAlg + Signature query params
 * per SAML Bindings §3.4.4.1 (unsigned XML + query-string signature).
 */
@Component
public class SamlAuthnRequestFactory {

    public static final String RSA_SHA256 = "http://www.w3.org/2001/04/xmldsig-more#rsa-sha256";

    private final SamlSpSigningCredentials signingCredentials;

    public SamlAuthnRequestFactory(SamlSpSigningCredentials signingCredentials) {
        this.signingCredentials = signingCredentials;
    }

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

        Optional<Saml2X509Credential> credential = signingCredentials.optionalCredential();
        if (credential.isEmpty() && !registration.getSigningX509Credentials().isEmpty()
                && registration.isAuthnRequestsSigned()) {
            credential = registration.getSigningX509Credentials().stream().findFirst();
        }

        if (credential.isPresent()) {
            return signedRedirect(destination, samlRequest, relayState, credential.get());
        }

        return UriComponentsBuilder
                .fromUriString(destination)
                .queryParam("SAMLRequest", samlRequest)
                .queryParam("RelayState", relayState)
                .encode()
                .build()
                .toUri();
    }

    /**
     * HTTP-Redirect signature over URL-encoded {@code SAMLRequest&RelayState&SigAlg}
     * (Signature itself is not part of the signed content).
     */
    public static URI signedRedirect(
            String destination,
            String samlRequest,
            String relayState,
            Saml2X509Credential credential) {
        String sigAlg = RSA_SHA256;
        String toSign = "SAMLRequest=" + uriEncode(samlRequest)
                + "&RelayState=" + uriEncode(relayState)
                + "&SigAlg=" + uriEncode(sigAlg);
        String signature = signQuery(toSign, credential);
        String query = toSign + "&Signature=" + uriEncode(signature);
        String base = destination.contains("?") ? destination + "&" : destination + "?";
        return URI.create(base + query);
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

    static String signQuery(String queryString, Saml2X509Credential credential) {
        try {
            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initSign(credential.getPrivateKey());
            signature.update(queryString.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(signature.sign());
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to sign SAML AuthnRequest redirect", ex);
        }
    }

    /** Encode query values the same way Spring Security signs Redirect bindings (ISO-8859-1 UriUtils). */
    public static String uriEncode(String value) {
        return UriUtils.encode(value, StandardCharsets.ISO_8859_1);
    }

    private static String xmlEscape(String value) {
        return value
                .replace("&", "&amp;")
                .replace("\"", "&quot;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }
}
