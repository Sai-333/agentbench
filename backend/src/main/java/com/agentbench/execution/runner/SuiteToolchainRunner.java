package com.agentbench.execution.runner;

import com.agentbench.execution.service.FixtureSuiteRunner;

import java.util.List;

/**
 * Language / framework suite runner SPI. Implementations execute tests inside
 * {@link com.agentbench.execution.service.ExecutionIsolatorService} and return the same
 * {@link FixtureSuiteRunner.CaseOutcome} model used by scoring (F2P/P2P/P2F/F2F on visible;
 * hidden stays post-only).
 */
public interface SuiteToolchainRunner {

    /** Stable id for logs / test-run metadata (e.g. {@code pytest}, {@code jest}, {@code maven-junit}). */
    String id();

    /** Whether this runner handles the suite {@code framework} string (case-insensitive). */
    boolean supports(String framework);

    /**
     * Run or ingest the suite. Callers should prefer existing on-disk JUnit XML when present
     * so CI demos and pre-seeded workspaces stay offline-friendly.
     */
    List<FixtureSuiteRunner.CaseOutcome> run(SuiteExecutionRequest request);
}
