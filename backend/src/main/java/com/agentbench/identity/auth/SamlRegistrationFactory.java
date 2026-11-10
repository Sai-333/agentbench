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
import java.util.concurrent.atomic.AtomicReference;

/**
 * Lazily builds a single {@link RelyingPartyRegistration} from IdP metadata URL or inline XML.
 * AuthnRequests are unsigned by default (IdP must allow that — common enterprise setting).
 */
@Component
public class SamlRegistrationFactory {

    private static final Logger log = LoggerFactory.getLogger(SamlRegistrationFactory.class);

    private final SamlProperties properties;
    private final AtomicReference<RelyingPartyRegistration> cached = new AtomicReference<>();

    public SamlRegistrationFactory(SamlProperties properties) {
        this.properties = properties;
    }

    public RelyingPartyRegistration requireRegistration() {
        if (!properties.isConfigured()) {
            throw ApiException.validation("SAML provider not configured");
        }
        RelyingPartyRegistration existing = cached.get();
        if (existing != null) {
            return existing;
        }
        synchronized (cached) {
            existing = cached.get();
            if (existing != null) {
                return existing;
            }
            RelyingPartyRegistration created = build();
            cached.set(created);
            return created;
        }
    }

    /** Clears cached registration (tests / metadata refresh). */
    public void evict() {
        cached.set(null);
    }

    private RelyingPartyRegistration build() {
        RelyingPartyRegistration.Builder builder;
        if (properties.getMetadataXml() != null && !properties.getMetadataXml().isBlank()) {
            builder = RelyingPartyRegistrations.fromMetadata(
                    new ByteArrayInputStream(properties.getMetadataXml().getBytes(StandardCharsets.UTF_8)));
        } else {
            log.info("Loading SAML IdP metadata from URL (entityId={})", properties.getEntityId());
            builder = RelyingPartyRegistrations.fromMetadataLocation(properties.getMetadataUrl().trim());
        }
        return builder
                .registrationId(properties.getRegistrationId())
                .entityId(properties.getEntityId().trim())
                .assertionConsumerServiceLocation(properties.getAcsUrl().trim())
                .assertionConsumerServiceBinding(Saml2MessageBinding.POST)
                .assertingPartyDetails(party -> party.wantAuthnRequestsSigned(false))
                .build();
    }
}
