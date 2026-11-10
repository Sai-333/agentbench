package com.agentbench.execution.runner;

import com.agentbench.execution.domain.ExecutionStage;
import com.agentbench.execution.service.FixtureSuiteRunner;
import com.fasterxml.jackson.databind.JsonNode;

import java.nio.file.Path;
import java.util.UUID;

/**
 * Context for a single baseline / visible-post / hidden-post suite execution.
 *
 * @param evaluationId       evaluation id (required for isolator-backed toolchain runs)
 * @param workspace          agent-visible or hidden-run workspace root
 * @param suiteNode          suite snapshot JSON (framework, entryCommand, fixtureContent, …)
 * @param phase              BASELINE or POST
 * @param stage              VISIBLE_TESTS or HIDDEN_TESTS (drives isolator mounts)
 * @param hiddenFixturesDir  optional hidden fixture mount (HIDDEN_TESTS only)
 * @param memoryMb           optional task memory override
 * @param cpuLimit           optional task CPU override
 * @param timeoutSec         optional task timeout override
 * @param networkEnabled     when true, allow container network (deps install); default false
 */
public record SuiteExecutionRequest(
        UUID evaluationId,
        Path workspace,
        JsonNode suiteNode,
        FixtureSuiteRunner.Phase phase,
        ExecutionStage stage,
        Path hiddenFixturesDir,
        Integer memoryMb,
        Double cpuLimit,
        Integer timeoutSec,
        boolean networkEnabled
) {
    public SuiteExecutionRequest {
        if (workspace == null) {
            throw new IllegalArgumentException("workspace required");
        }
        if (suiteNode == null) {
            throw new IllegalArgumentException("suiteNode required");
        }
        if (phase == null) {
            throw new IllegalArgumentException("phase required");
        }
        if (stage == null) {
            stage = ExecutionStage.VISIBLE_TESTS;
        }
    }

    /** Convenience for fixture / XML-only paths that do not need isolator context. */
    public static SuiteExecutionRequest offline(Path workspace, JsonNode suiteNode, FixtureSuiteRunner.Phase phase) {
        return new SuiteExecutionRequest(
                null,
                workspace,
                suiteNode,
                phase,
                ExecutionStage.VISIBLE_TESTS,
                null,
                null,
                null,
                null,
                false
        );
    }

    public String framework() {
        return suiteNode.path("framework").asText("").trim().toLowerCase();
    }

    public String entryCommand() {
        return suiteNode.path("entryCommand").asText("").trim();
    }

    public boolean canInvokeIsolator() {
        return evaluationId != null;
    }
}
