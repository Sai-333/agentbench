package com.agentbench.evaluation.domain;

import java.time.Instant;
import java.util.UUID;

public record TestRun(
        UUID id,
        UUID evaluationId,
        UUID orgId,
        TestPhase phase,
        UUID suiteId,
        String status,
        Instant startedAt,
        Instant finishedAt,
        String containerMetaJson
) {
}
