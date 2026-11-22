package com.agentbench.platform.gpu;

/**
 * Runs the local graphics probe once. Implementations must not invent a score.
 */
public interface GpuGraphicsProbe {

    GpuGraphicsResult probe();
}
