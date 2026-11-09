package com.agentbench.projects.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.UUID;

public final class ProjectDtos {

    private ProjectDtos() {
    }

    public record CreateProjectRequest(
            @NotBlank @Size(min = 1, max = 120) String name,
            @Size(max = 2000) String description
    ) {
    }

    public record UpdateProjectRequest(
            @NotBlank @Size(min = 1, max = 120) String name,
            @Size(max = 2000) String description
    ) {
    }

    public record ProjectResponse(
            UUID id,
            UUID orgId,
            String name,
            String description,
            UUID createdBy,
            Instant createdAt,
            Instant updatedAt
    ) {
    }

    public record UpsertRepositoryRequest(
            @NotBlank @Size(min = 3, max = 500) String gitUrl,
            @NotBlank @Size(min = 1, max = 200) String defaultBranch,
            @Pattern(regexp = "^$|^[0-9a-fA-F]{40}$", message = "commitSha must be 40 hex chars when set")
            String commitSha,
            @Size(max = 80) String language,
            @Size(max = 500) String buildCommand,
            @Size(max = 500) String testCommand,
            UUID secretRefId,
            Boolean validate
    ) {
    }

    public record RepositoryResponse(
            UUID id,
            UUID projectId,
            String gitUrl,
            String defaultBranch,
            String commitSha,
            String language,
            String buildCommand,
            String testCommand,
            UUID secretRefId,
            boolean hasCredentials,
            String validationStatus,
            String validationMessage,
            Instant lastValidatedAt,
            Instant updatedAt
    ) {
    }

    public record CreateSnapshotRequest(
            String gitUrl,
            @Pattern(regexp = "^$|^[0-9a-fA-F]{40}$") String commitSha,
            String branch,
            String contentDigest,
            String storageUri
    ) {
    }

    public record SnapshotResponse(
            UUID id,
            UUID projectId,
            String gitUrl,
            String commitSha,
            String branch,
            String contentDigest,
            String storageUri,
            Instant createdAt
    ) {
    }
}
