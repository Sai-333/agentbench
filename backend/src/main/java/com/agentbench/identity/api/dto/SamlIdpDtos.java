package com.agentbench.identity.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.UUID;

public final class SamlIdpDtos {

    private SamlIdpDtos() {
    }

    public record CreateRequest(
            @NotBlank
            @Pattern(regexp = "^[a-z0-9]+(?:-[a-z0-9]+)*$", message = "registrationId must be lowercase kebab-case")
            @Size(min = 2, max = 63)
            String registrationId,
            @NotBlank @Size(min = 1, max = 120) String displayName,
            @Size(max = 2048) String metadataUrl,
            @Size(max = 524288) String metadataXml,
            @Size(max = 256) String attrEmail,
            @Size(max = 256) String attrDisplayName,
            Boolean enabled
    ) {
    }

    public record UpdateRequest(
            @NotBlank @Size(min = 1, max = 120) String displayName,
            @Size(max = 2048) String metadataUrl,
            @Size(max = 524288) String metadataXml,
            @Size(max = 256) String attrEmail,
            @Size(max = 256) String attrDisplayName,
            @NotNull Boolean enabled
    ) {
    }

    public record IdpResponse(
            UUID id,
            UUID orgId,
            String registrationId,
            String displayName,
            String metadataUrl,
            boolean hasMetadataXml,
            String attrEmail,
            String attrDisplayName,
            boolean enabled,
            Instant createdAt,
            Instant updatedAt
    ) {
    }
}
