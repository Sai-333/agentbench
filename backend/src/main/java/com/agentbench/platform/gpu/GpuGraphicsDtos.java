package com.agentbench.platform.gpu;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public final class GpuGraphicsDtos {

    private GpuGraphicsDtos() {
    }

    public record Result(
            UUID id,
            String status,
            String device,
            Integer frames,
            BigDecimal frameTimeMs,
            BigDecimal score,
            String scoreUnit,
            String unavailableReason,
            String probe,
            String detail,
            Instant probedAt
    ) {
    }
}
