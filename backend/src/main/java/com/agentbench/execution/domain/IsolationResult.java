package com.agentbench.execution.domain;

import java.util.Map;

public record IsolationResult(
        int exitCode,
        String stdout,
        String stderr,
        boolean timedOut,
        boolean killed,
        String runtime,
        Map<String, Object> meta
) {
    public boolean succeeded() {
        return !timedOut && !killed && exitCode == 0;
    }
}
