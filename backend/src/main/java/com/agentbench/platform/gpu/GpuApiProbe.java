package com.agentbench.platform.gpu;

interface GpuApiProbe {

    String api();

    GpuApiAttempt attempt();
}
