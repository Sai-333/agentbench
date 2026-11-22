package com.agentbench.platform.gpu;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public final class GpuBenchmarkDtos {

    private GpuBenchmarkDtos() {
    }

    public record Result(
            UUID id,
            String status,
            String device,
            String api,
            BigDecimal score,
            String scoreUnit,
            String unavailableReason,
            String probe,
            String detail,
            Instant probedAt
    ) {
    }
}
