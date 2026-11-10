package com.agentbench.identity.service;

import com.agentbench.identity.api.dto.AuthDtos;
import com.agentbench.identity.auth.SamlAuthProvider;
import com.agentbench.identity.auth.SamlAuthnRequestFactory;
import com.agentbench.identity.auth.SamlProperties;
import com.agentbench.identity.auth.SamlRegistrationFactory;
import com.agentbench.identity.auth.SamlResponseProcessor;
import com.agentbench.identity.auth.SamlUserProfile;
import com.agentbench.identity.domain.SamlIdentityProvider;
import com.agentbench.identity.infra.OidcLoginStateRepository;
import com.agentbench.platform.error.ApiException;
import org.springframework.security.saml2.provider.service.registration.RelyingPartyRegistration;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;

@Service
public class SamlAuthService {

    private static final long STATE_TTL_SECONDS = 600L;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final SamlProperties properties;
    private final SamlAuthProvider samlAuthProvider;
    private final SamlRegistrationFactory registrationFactory;
    private final SamlAuthnRequestFactory authnRequestFactory;
    private final SamlResponseProcessor responseProcessor;
    private final OidcLoginStateRepository loginStateRepository;
    private final AuthService authService;
    private final SamlIdpAdminService idpAdminService;

    public SamlAuthService(
            SamlProperties properties,
            SamlAuthProvider samlAuthProvider,
            SamlRegistrationFactory registrationFactory,
            SamlAuthnRequestFactory authnRequestFactory,
            SamlResponseProcessor responseProcessor,
            OidcLoginStateRepository loginStateRepository,
            AuthService authService,
            SamlIdpAdminService idpAdminService) {
        this.properties = properties;
        this.samlAuthProvider = samlAuthProvider;
        this.registrationFactory = registrationFactory;
        this.authnRequestFactory = authnRequestFactory;
        this.responseProcessor = responseProcessor;
        this.loginStateRepository = loginStateRepository;
        this.authService = authService;
        this.idpAdminService = idpAdminService;
    }

    public void requireEnabled() {
        if (!properties.isSpReady()) {
            throw ApiException.validation("Auth provider not configured: saml");
        }
        boolean hasDb = !idpAdminService.listEnabledForLogin().isEmpty();
        if (!hasDb && !properties.isEnvIdpConfigured()) {
            throw ApiException.validation("Auth provider not configured: saml");
        }
    }

    /** Env/props fallback login (no registration path segment). */
    @Transactional
    public URI beginLogin() {
        requireEnvFallback();
        return beginWithEnvIdp();
    }

    @Transactional
    public URI beginLogin(String registrationId) {
        requireEnabled();
        String id = registrationId == null ? "" : registrationId.trim().toLowerCase(Locale.ROOT);
        if (id.isBlank()) {
            return beginLogin();
        }
        if (id.equalsIgnoreCase(properties.getRegistrationId()) && properties.isEnvIdpConfigured()) {
            return beginWithEnvIdp();
        }
        SamlIdentityProvider idp = idpAdminService.requireEnabledByRegistrationId(id);
        RelyingPartyRegistration registration = registrationFactory.requireRegistration(
                idp.registrationId(),
                idp.metadataUrl(),
                idp.metadataXml()
        );
        String relayState = randomToken();
        String authnRequestId = "_" + randomToken();
        loginStateRepository.insert(
                relayState,
                loginProviderKey(idp.registrationId()),
                authnRequestId,
                properties.getSuccessRedirectUrl(),
                Instant.now().plusSeconds(STATE_TTL_SECONDS)
        );
        return authnRequestFactory.buildRedirect(registration, authnRequestId, relayState);
    }

    @Transactional
    public AuthDtos.TokenResponse completeWithResponse(String samlResponse, String relayState) {
        return complete(samlResponse, relayState).tokens();
    }

    @Transactional
    public URI completeBrowserAcs(String samlResponse, String relayState) {
        Completion completion = complete(samlResponse, relayState);
        return buildSuccessRedirect(completion.redirectTo(), completion.tokens());
    }

    private URI beginWithEnvIdp() {
        RelyingPartyRegistration registration = registrationFactory.requireRegistration();
        String relayState = randomToken();
        String authnRequestId = "_" + randomToken();
        loginStateRepository.insert(
                relayState,
                SamlAuthProvider.TYPE,
                authnRequestId,
                properties.getSuccessRedirectUrl(),
                Instant.now().plusSeconds(STATE_TTL_SECONDS)
        );
        return authnRequestFactory.buildRedirect(registration, authnRequestId, relayState);
    }

    private Completion complete(String samlResponse, String relayState) {
        requireEnabled();
        if (relayState == null || relayState.isBlank()) {
            throw ApiException.validation("RelayState is required");
        }
        var consumed = loginStateRepository.consumeSaml(relayState.trim())
                .orElseThrow(() -> ApiException.unauthorized("Invalid or expired SAML RelayState"));

        ResolvedIdp resolved = resolveIdp(consumed.provider());
        RelyingPartyRegistration registration = registrationFactory.requireRegistration(
                resolved.registrationId(),
                resolved.metadataUrl(),
                resolved.metadataXml()
        );
        SamlProperties.AttributeMapping mapping = new SamlProperties.AttributeMapping();
        mapping.setEmail(resolved.attrEmail());
        mapping.setDisplayName(resolved.attrDisplayName());
        SamlUserProfile profile = responseProcessor.process(
                registration, samlResponse, consumed.nonce(), mapping);
        var principal = samlAuthProvider.linkOrCreate(
                profile, resolved.registrationId(), resolved.scopeSubject());
        AuthDtos.TokenResponse tokens = authService.issueTokensForPrincipal(principal.userId(), principal.email());
        String redirectTo = consumed.redirectTo() == null || consumed.redirectTo().isBlank()
                ? properties.getSuccessRedirectUrl()
                : consumed.redirectTo();
        return new Completion(tokens, redirectTo);
    }

    private ResolvedIdp resolveIdp(String loginProvider) {
        if (loginProvider == null || SamlAuthProvider.TYPE.equals(loginProvider)) {
            requireEnvFallback();
            return new ResolvedIdp(
                    properties.getRegistrationId(),
                    properties.getMetadataUrl(),
                    properties.getMetadataXml(),
                    properties.getAttributeMapping().getEmail(),
                    properties.getAttributeMapping().getDisplayName(),
                    false
            );
        }
        if (loginProvider.startsWith("saml:")) {
            String registrationId = loginProvider.substring("saml:".length());
            SamlIdentityProvider idp = idpAdminService.requireEnabledByRegistrationId(registrationId);
            return new ResolvedIdp(
                    idp.registrationId(),
                    idp.metadataUrl(),
                    idp.metadataXml(),
                    idp.attrEmail(),
                    idp.attrDisplayName(),
                    true
            );
        }
        throw ApiException.unauthorized("Invalid or expired SAML RelayState");
    }

    private void requireEnvFallback() {
        if (!properties.isEnvIdpConfigured()) {
            throw ApiException.validation("Auth provider not configured: saml");
        }
    }

    /**
     * Minimal SP metadata for IdP registration (EntityDescriptor with ACS).
     */
    public String spMetadataXml() {
        requireEnabled();
        String entityId = xmlEscape(properties.getEntityId().trim());
        String acs = xmlEscape(properties.getAcsUrl().trim());
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <md:EntityDescriptor xmlns:md="urn:oasis:names:tc:SAML:2.0:metadata" entityID="%s">
                  <md:SPSSODescriptor AuthnRequestsSigned="false" WantAssertionsSigned="true"
                      protocolSupportEnumeration="urn:oasis:names:tc:SAML:2.0:protocol">
                    <md:NameIDFormat>urn:oasis:names:tc:SAML:1.1:nameid-format:emailAddress</md:NameIDFormat>
                    <md:AssertionConsumerService Binding="urn:oasis:names:tc:SAML:2.0:bindings:HTTP-POST"
                        Location="%s" index="0" isDefault="true"/>
                  </md:SPSSODescriptor>
                </md:EntityDescriptor>
                """.formatted(entityId, acs);
    }

    static URI buildSuccessRedirect(String base, AuthDtos.TokenResponse tokens) {
        String fragment = UriComponentsBuilder.newInstance()
                .queryParam("access_token", tokens.accessToken())
                .queryParam("refresh_token", tokens.refreshToken())
                .queryParam("token_type", tokens.tokenType())
                .queryParam("expires_in", tokens.expiresInSeconds())
                .build()
                .getQuery();
        String normalized = base.contains("#") ? base.substring(0, base.indexOf('#')) : base;
        return URI.create(normalized + "#" + fragment);
    }

    static String loginProviderKey(String registrationId) {
        return "saml:" + registrationId;
    }

    private static String randomToken() {
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    private static String xmlEscape(String value) {
        return value
                .replace("&", "&amp;")
                .replace("\"", "&quot;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }

    private record Completion(AuthDtos.TokenResponse tokens, String redirectTo) {
    }

    private record ResolvedIdp(
            String registrationId,
            String metadataUrl,
            String metadataXml,
            String attrEmail,
            String attrDisplayName,
            boolean scopeSubject
    ) {
    }
}
