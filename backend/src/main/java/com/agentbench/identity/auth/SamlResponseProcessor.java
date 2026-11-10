package com.agentbench.identity.auth;

import com.agentbench.platform.error.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.saml2.core.OpenSamlInitializationService;
import org.springframework.security.saml2.core.Saml2Error;
import org.springframework.security.saml2.provider.service.authentication.OpenSaml4AuthenticationProvider;
import org.springframework.security.saml2.provider.service.authentication.Saml2AuthenticatedPrincipal;
import org.springframework.security.saml2.provider.service.authentication.Saml2AuthenticationException;
import org.springframework.security.saml2.provider.service.authentication.Saml2AuthenticationToken;
import org.springframework.security.saml2.provider.service.authentication.Saml2RedirectAuthenticationRequest;
import org.springframework.security.saml2.provider.service.registration.RelyingPartyRegistration;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Locale;

/**
 * Validates a SAMLResponse with Spring Security OpenSAML and maps attributes to {@link SamlUserProfile}.
 */
@Component
public class SamlResponseProcessor {

    private static final Logger log = LoggerFactory.getLogger(SamlResponseProcessor.class);

    static {
        OpenSamlInitializationService.initialize();
    }

    private final SamlProperties properties;
    private final OpenSaml4AuthenticationProvider authenticationProvider = new OpenSaml4AuthenticationProvider();

    public SamlResponseProcessor(SamlProperties properties) {
        this.properties = properties;
    }

    public SamlUserProfile process(
            RelyingPartyRegistration registration,
            String samlResponseParam,
            String authnRequestId) {
        if (samlResponseParam == null || samlResponseParam.isBlank()) {
            throw ApiException.validation("SAMLResponse is required");
        }
        String responseXml = decodeSamlResponse(samlResponseParam.trim());
        // samlRequest body is not re-validated here; id is used for InResponseTo matching.
        Saml2RedirectAuthenticationRequest authenticationRequest = Saml2RedirectAuthenticationRequest
                .withRelyingPartyRegistration(registration)
                .id(authnRequestId == null || authnRequestId.isBlank() ? "_unknown" : authnRequestId)
                .samlRequest("placeholder")
                .relayState("placeholder")
                .authenticationRequestUri(registration.getAssertingPartyDetails().getSingleSignOnServiceLocation())
                .build();
        Saml2AuthenticationToken token = new Saml2AuthenticationToken(registration, responseXml, authenticationRequest);
        try {
            Authentication authentication = authenticationProvider.authenticate(token);
            if (!(authentication.getPrincipal() instanceof Saml2AuthenticatedPrincipal principal)) {
                throw ApiException.unauthorized("SAML authentication failed");
            }
            return toProfile(principal);
        } catch (Saml2AuthenticationException ex) {
            Saml2Error error = ex.getSaml2Error();
            log.warn("SAML response rejected: code={} description={}",
                    error != null ? error.getErrorCode() : "unknown",
                    error != null ? error.getDescription() : ex.getMessage());
            throw ApiException.unauthorized("Invalid SAML response");
        } catch (ApiException ex) {
            throw ex;
        } catch (Exception ex) {
            log.warn("SAML response processing error: {}", ex.toString());
            throw ApiException.unauthorized("Invalid SAML response");
        }
    }

    private SamlUserProfile toProfile(Saml2AuthenticatedPrincipal principal) {
        String subject = principal.getName();
        if (subject == null || subject.isBlank()) {
            throw ApiException.unauthorized("SAML assertion missing NameID");
        }
        String emailAttr = properties.getAttributeMapping().getEmail();
        String nameAttr = properties.getAttributeMapping().getDisplayName();
        String email = firstAttribute(principal, emailAttr);
        if (email == null || email.isBlank()) {
            // Common enterprise fallback: NameID is an emailAddress format.
            if (subject.contains("@")) {
                email = subject;
            } else {
                log.warn("SAML assertion missing email attribute={}", emailAttr);
                throw ApiException.unauthorized("SAML account email is required");
            }
        }
        email = email.trim().toLowerCase(Locale.ROOT);
        String displayName = firstAttribute(principal, nameAttr);
        if (displayName == null || displayName.isBlank()) {
            displayName = email;
        } else {
            displayName = displayName.trim();
        }
        return new SamlUserProfile(subject.trim(), email, displayName);
    }

    private static String firstAttribute(Saml2AuthenticatedPrincipal principal, String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        Object value = principal.getFirstAttribute(name);
        if (value == null) {
            return null;
        }
        if (value instanceof List<?> list) {
            return list.isEmpty() || list.getFirst() == null ? null : String.valueOf(list.getFirst());
        }
        return String.valueOf(value);
    }

    static String decodeSamlResponse(String encoded) {
        try {
            byte[] decoded = Base64.getDecoder().decode(encoded.replaceAll("\\s", ""));
            return new String(decoded, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ex) {
            // Some IdPs / test clients may already send XML.
            if (encoded.contains("<")) {
                return encoded;
            }
            throw ApiException.validation("SAMLResponse is not valid Base64");
        }
    }
}
