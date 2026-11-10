package com.agentbench.execution.domain;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Hardened run request for an evaluation container / local isolator.
 * Callers must never request privileged mode or docker.sock mounts.
 *
 * @param imageOverride optional container image (tag or digest). When null/blank, the
 *                      isolator uses {@code agentbench.execution} defaults.
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
        Map<String, String> env,
        String imageOverride
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
        if (imageOverride != null && imageOverride.isBlank()) {
            imageOverride = null;
        }
    }

    /** Convenience constructor without image override (execution defaults). */
    public IsolationSpec(
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
            Map<String, String> env) {
        this(runName, stage, workspace, hiddenFixturesDir, command, timeout, memoryMb, cpuLimit,
                pidsLimit, networkEnabled, readOnlyRootFs, env, null);
    }
}
