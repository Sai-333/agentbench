package com.agentbench.identity.auth;

import com.agentbench.platform.error.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.saml2.provider.service.registration.RelyingPartyRegistration;
import org.springframework.security.saml2.provider.service.registration.RelyingPartyRegistrations;
import org.springframework.security.saml2.provider.service.registration.Saml2MessageBinding;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Builds {@link RelyingPartyRegistration} instances from IdP metadata URL or inline XML.
 * AuthnRequests are unsigned by default (IdP must allow that — common enterprise setting).
 * Cache is keyed by registration id so multi-IdP configs stay independent.
 */
@Component
public class SamlRegistrationFactory {

    private static final Logger log = LoggerFactory.getLogger(SamlRegistrationFactory.class);

    private final SamlProperties properties;
    private final ConcurrentHashMap<String, RelyingPartyRegistration> cache = new ConcurrentHashMap<>();

    public SamlRegistrationFactory(SamlProperties properties) {
        this.properties = properties;
    }

    public RelyingPartyRegistration requireRegistration() {
        if (!properties.isEnvIdpConfigured()) {
            throw ApiException.validation("SAML provider not configured");
        }
        return requireRegistration(
                properties.getRegistrationId(),
                properties.getMetadataUrl(),
                properties.getMetadataXml()
        );
    }

    public RelyingPartyRegistration requireRegistration(
            String registrationId,
            String metadataUrl,
            String metadataXml) {
        if (!properties.isSpReady()) {
            throw ApiException.validation("SAML SP not configured");
        }
        if (registrationId == null || registrationId.isBlank()) {
            throw ApiException.validation("SAML registration id is required");
        }
        boolean hasMetadata = (metadataUrl != null && !metadataUrl.isBlank())
                || (metadataXml != null && !metadataXml.isBlank());
        if (!hasMetadata) {
            throw ApiException.validation("SAML IdP metadata is required");
        }
        String key = registrationId.trim();
        return cache.computeIfAbsent(key, id -> build(id, metadataUrl, metadataXml));
    }

    /** Clears cached registrations (tests / metadata refresh after admin edits). */
    public void evict() {
        cache.clear();
    }

    public void evict(String registrationId) {
        if (registrationId != null) {
            cache.remove(registrationId.trim());
        }
    }

    private RelyingPartyRegistration build(String registrationId, String metadataUrl, String metadataXml) {
        RelyingPartyRegistration.Builder builder;
        if (metadataXml != null && !metadataXml.isBlank()) {
            builder = RelyingPartyRegistrations.fromMetadata(
                    new ByteArrayInputStream(metadataXml.getBytes(StandardCharsets.UTF_8)));
        } else {
            log.info("Loading SAML IdP metadata from URL (registrationId={}, entityId={})",
                    registrationId, properties.getEntityId());
            builder = RelyingPartyRegistrations.fromMetadataLocation(Objects.requireNonNull(metadataUrl).trim());
        }
        return builder
                .registrationId(registrationId)
                .entityId(properties.getEntityId().trim())
                .assertionConsumerServiceLocation(properties.getAcsUrl().trim())
                .assertionConsumerServiceBinding(Saml2MessageBinding.POST)
                .assertingPartyDetails(party -> party.wantAuthnRequestsSigned(false))
                .build();
    }
}
