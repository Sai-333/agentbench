package com.agentbench.platform.gpu;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * MiB/s from a timed copy of a fixed buffer. The same byte count is used for
 * every API so the unit means the same thing. Values that are not a real
 * positive measurement are rejected instead of clamped.
 */
final class GpuScores {

    static final int BUFFER_BYTES = 8 * 1024 * 1024;
    static final int WARMUP_ITERS = 1;
    static final int TIMED_ITERS = 4;
    static final BigDecimal MAX_MIB_PER_SEC = new BigDecimal("1000000");

    private GpuScores() {
    }

    /**
     * Bytes moved per timed iteration are {@code BUFFER_BYTES} each way.
     * {@code nanos} is the duration of {@link #TIMED_ITERS} iterations only.
     */
    static BigDecimal mibPerSec(long nanos) {
        if (nanos <= 0) {
            return null;
        }
        BigDecimal bytes = BigDecimal.valueOf((long) BUFFER_BYTES * 2L * TIMED_ITERS);
        BigDecimal mib = bytes.divide(BigDecimal.valueOf(1024L * 1024L), 8, RoundingMode.HALF_UP);
        BigDecimal seconds = BigDecimal.valueOf(nanos).divide(BigDecimal.valueOf(1_000_000_000L), 9, RoundingMode.HALF_UP);
        if (seconds.signum() <= 0) {
            return null;
        }
        BigDecimal value = mib.divide(seconds, 2, RoundingMode.HALF_UP);
        return valid(value) ? value : null;
    }

    static boolean valid(BigDecimal score) {
        return score != null
                && score.signum() > 0
                && score.compareTo(MAX_MIB_PER_SEC) <= 0;
    }
}
