package com.agentbench.projects.domain;

import java.time.Instant;
import java.util.UUID;

public record RepoSnapshot(
        UUID id,
        UUID orgId,
        UUID projectId,
        String gitUrl,
        String commitSha,
        String branch,
        String contentDigest,
        String storageUri,
        Instant createdAt
) {
}
