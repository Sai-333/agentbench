package com.agentbench.projects.domain;

import java.time.Instant;
import java.util.UUID;

public record ProjectRepositoryConfig(
        UUID id,
        UUID orgId,
        UUID projectId,
        String gitUrl,
        String defaultBranch,
        String commitSha,
        String language,
        String buildCommand,
        String testCommand,
        UUID secretRefId,
        String validationStatus,
        String validationMessage,
        Instant lastValidatedAt,
        Instant createdAt,
        Instant updatedAt
) {
}
