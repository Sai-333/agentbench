package com.agentbench.identity.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * Org-scoped SAML IdP configuration row. {@code registrationId} is globally unique for login URLs.
 */
public record SamlIdentityProvider(
        UUID id,
        UUID orgId,
        String registrationId,
        String displayName,
        String metadataUrl,
        String metadataXml,
        String attrEmail,
        String attrDisplayName,
        boolean enabled,
        Instant createdAt,
        Instant updatedAt
) {
    public boolean hasMetadata() {
        return (metadataUrl != null && !metadataUrl.isBlank())
                || (metadataXml != null && !metadataXml.isBlank());
    }
}
