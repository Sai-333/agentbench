package com.agentbench.execution.service;

import java.time.Duration;
import java.util.List;

/**
 * Thin wrapper around host process execution so isolators can probe
 * {@code docker}/{@code runsc}/{@code firecracker}/KVM and run workloads without binding
 * tests to a real daemon or microVM.
 */
public interface HostCommandExecutor {

    record Result(int exitCode, String stdout, String stderr, boolean timedOut, boolean started) {
        public static Result failedToStart(String message) {
            return new Result(-1, "", message == null ? "" : message, false, false);
        }
    }

    Result run(List<String> command, Duration timeout);
}
