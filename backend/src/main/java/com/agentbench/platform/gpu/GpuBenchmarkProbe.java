package com.agentbench.platform.gpu;

/**
 * Runs the local GPU probe once. Implementations must not invent a score.
 */
public interface GpuBenchmarkProbe {

    GpuBenchmarkResult probe();
}
