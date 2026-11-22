package com.agentbench.platform.gpu;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * One local graphics probe. A measured row names the device, the frame count,
 * and a positive average frame time. That average is the score. An unavailable
 * row has a null score.
 */
public record GpuGraphicsResult(
        String status,
        String device,
        Integer frames,
        BigDecimal frameTimeMs,
        String unavailableReason,
        String detail
) {
    public static final String STATUS_MEASURED = "measured";
    public static final String STATUS_UNAVAILABLE = "unavailable";
    public static final String REASON_NO_GPU = "no_gpu";
    public static final String REASON_PROBE_FAILED = "probe_failed";
    public static final String PROBE_NAME = "agentbench-gpu-graphics-v1";
    public static final String SCORE_UNIT = "ms";

    public GpuGraphicsResult {
        device = GpuText.device(device);
        detail = GpuText.detail(detail);
        if (STATUS_MEASURED.equals(status)) {
            if (device == null
                    || frames == null
                    || frames != GpuGraphicsScores.FRAMES
                    || !GpuGraphicsScores.valid(frameTimeMs)) {
                throw new IllegalArgumentException("measured graphics result is incomplete");
            }
            if (unavailableReason != null || detail != null) {
                throw new IllegalArgumentException("measured graphics result has no failure fields");
            }
            frameTimeMs = frameTimeMs.setScale(2, RoundingMode.HALF_UP);
            if (!GpuGraphicsScores.valid(frameTimeMs)) {
                throw new IllegalArgumentException("measured graphics frame time is out of range");
            }
        } else if (STATUS_UNAVAILABLE.equals(status)) {
            if (frameTimeMs != null || frames != null) {
                throw new IllegalArgumentException("unavailable graphics result cannot carry a score");
            }
            if (!REASON_NO_GPU.equals(unavailableReason) && !REASON_PROBE_FAILED.equals(unavailableReason)) {
                throw new IllegalArgumentException("unavailable graphics result needs a reason");
            }
            if (REASON_NO_GPU.equals(unavailableReason) && device != null) {
                throw new IllegalArgumentException("no_gpu result cannot name a device");
            }
        } else {
            throw new IllegalArgumentException("unknown graphics probe status");
        }
    }

    public static GpuGraphicsResult measured(String device, int frames, BigDecimal frameTimeMs) {
        return new GpuGraphicsResult(STATUS_MEASURED, device, frames, frameTimeMs, null, null);
    }

    public static GpuGraphicsResult unavailable(String device, String reason, String detail) {
        return new GpuGraphicsResult(STATUS_UNAVAILABLE, device, null, null, reason, detail);
    }

    public BigDecimal score() {
        return STATUS_MEASURED.equals(status) ? frameTimeMs : null;
    }

    public String scoreUnit() {
        return STATUS_MEASURED.equals(status) ? SCORE_UNIT : null;
    }
}
