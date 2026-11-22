package com.agentbench.platform.gpu;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Average frame time for a fixed clear. The frame count does not change, so
 * the millisecond value means the same thing on every row. Values that are not
 * a real positive measurement are rejected instead of clamped.
 */
final class GpuGraphicsScores {

    static final int FRAMES = 32;
    static final int WARMUP_FRAMES = 2;
    static final int WIDTH = 64;
    static final int HEIGHT = 64;
    static final BigDecimal MAX_FRAME_MS = new BigDecimal("10000");

    private GpuGraphicsScores() {
    }

    /**
     * {@code nanos} is the duration of {@link #FRAMES} timed frames only.
     * The result is milliseconds per frame, at two decimal places.
     */
    static BigDecimal frameTimeMs(long nanos, int frames) {
        if (nanos <= 0 || frames != FRAMES) {
            return null;
        }
        BigDecimal millis = BigDecimal.valueOf(nanos)
                .divide(BigDecimal.valueOf(1_000_000L), 8, RoundingMode.HALF_UP);
        BigDecimal perFrame = millis.divide(BigDecimal.valueOf(frames), 2, RoundingMode.HALF_UP);
        return valid(perFrame) ? perFrame : null;
    }

    static boolean valid(BigDecimal frameTimeMs) {
        return frameTimeMs != null
                && frameTimeMs.signum() > 0
                && frameTimeMs.compareTo(MAX_FRAME_MS) <= 0;
    }
}
