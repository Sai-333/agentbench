package com.agentbench.execution.domain;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Hardened run request for an evaluation container / local isolator.
 * Callers must never request privileged mode or docker.sock mounts.
 */
public record IsolationSpec(
        String runName,
        ExecutionStage stage,
        Path workspace,
        Path hiddenFixturesDir,
        List<String> command,
        Duration timeout,
        int memoryMb,
        double cpuLimit,
        int pidsLimit,
        boolean networkEnabled,
        boolean readOnlyRootFs,
        Map<String, String> env
) {
    public IsolationSpec {
        if (command == null || command.isEmpty()) {
            throw new IllegalArgumentException("command required");
        }
        if (workspace == null) {
            throw new IllegalArgumentException("workspace required");
        }
        if (timeout == null || timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("positive timeout required");
        }
        env = env == null ? Map.of() : Map.copyOf(env);
        command = List.copyOf(command);
    }
}
