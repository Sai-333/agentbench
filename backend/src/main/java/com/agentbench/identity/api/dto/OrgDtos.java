package com.agentbench.identity.api.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.UUID;

public final class OrgDtos {

    private OrgDtos() {
    }

    public record CreateOrgRequest(
            @NotBlank @Size(min = 2, max = 120) String name,
            @NotBlank
            @Pattern(regexp = "^[a-z0-9]+(?:-[a-z0-9]+)*$", message = "slug must be lowercase kebab-case")
            @Size(min = 2, max = 63)
            String slug
    ) {
    }

    public record OrgResponse(UUID id, String name, String slug, Instant createdAt) {
    }

    public record MemberResponse(UUID userId, String email, String displayName, String role, Instant createdAt) {
    }

    public record AddMemberRequest(
            @NotBlank @Email String email,
            @NotBlank @Pattern(regexp = "ADMIN|DEVELOPER|VIEWER", message = "role must be ADMIN, DEVELOPER, or VIEWER")
            String role
    ) {
    }

    public record UpdateMemberRequest(
            @NotBlank @Pattern(regexp = "OWNER|ADMIN|DEVELOPER|VIEWER") String role
    ) {
    }

    public record CreateSecretRequest(
            @NotBlank @Size(min = 1, max = 100) String name,
            @NotBlank @Pattern(regexp = "GIT|AGENT|OTHER") String purpose,
            @NotBlank @Size(min = 1, max = 8192) String plaintext
    ) {
    }

    public record RotateSecretRequest(@NotBlank @Size(min = 1, max = 8192) String plaintext) {
    }

    public record SecretResponse(UUID id, String name, String purpose, Instant createdAt, Instant rotatedAt) {
    }
}
