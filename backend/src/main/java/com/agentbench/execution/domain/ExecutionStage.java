package com.agentbench.execution.domain;

/**
 * Distinct execution stages so agent context, visible tests, hidden tests, and quality
 * analysis never share mounts / filesystem views incorrectly.
 */
public enum ExecutionStage {
    /** Agent invocation workspace — never includes hidden fixtures. */
    AGENT,
    /** Baseline or visible post tests on the evaluation workspace. */
    VISIBLE_TESTS,
    /** Hidden suite after patch seal — fixtures mounted only for this stage. */
    HIDDEN_TESTS,
    /** Static analysis (Checkstyle / SpotBugs / PMD; optional Sonar / ruff / eslint). */
    QUALITY
}
