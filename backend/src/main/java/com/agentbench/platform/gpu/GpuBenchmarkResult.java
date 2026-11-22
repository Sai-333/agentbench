package com.agentbench.platform.gpu;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Set;

/**
 * One local GPU probe. A measured row names the device, the API that ran, and a
 * positive score. An unavailable row has a null score.
 */
public record GpuBenchmarkResult(
        String status,
        String device,
        String api,
        BigDecimal score,
        String unavailableReason,
        String detail
) {
    public static final String STATUS_MEASURED = "measured";
    public static final String STATUS_UNAVAILABLE = "unavailable";
    public static final String REASON_NO_GPU = "no_gpu";
    public static final String REASON_PROBE_FAILED = "probe_failed";
    public static final String PROBE_NAME = "agentbench-gpu-probe-v1";
    public static final String SCORE_UNIT = "mib_per_s";
    public static final Set<String> APIS = Set.of("CUDA", "Vulkan", "OpenCL");

    public GpuBenchmarkResult {
        device = GpuText.device(device);
        detail = GpuText.detail(detail);
        if (STATUS_MEASURED.equals(status)) {
            if (device == null || api == null || !APIS.contains(api) || !GpuScores.valid(score)) {
                throw new IllegalArgumentException("measured GPU result is incomplete");
            }
            if (unavailableReason != null || detail != null) {
                throw new IllegalArgumentException("measured GPU result has no failure fields");
            }
            score = score.setScale(2, RoundingMode.HALF_UP);
            if (!GpuScores.valid(score)) {
                throw new IllegalArgumentException("measured GPU score is out of range");
            }
        } else if (STATUS_UNAVAILABLE.equals(status)) {
            if (score != null || api != null) {
                throw new IllegalArgumentException("unavailable GPU result cannot carry a score");
            }
            if (!REASON_NO_GPU.equals(unavailableReason) && !REASON_PROBE_FAILED.equals(unavailableReason)) {
                throw new IllegalArgumentException("unavailable GPU result needs a reason");
            }
            if (REASON_NO_GPU.equals(unavailableReason) && device != null) {
                throw new IllegalArgumentException("no_gpu result cannot name a device");
            }
        } else {
            throw new IllegalArgumentException("unknown GPU probe status");
        }
    }

    public static GpuBenchmarkResult measured(String device, String api, BigDecimal score) {
        return new GpuBenchmarkResult(STATUS_MEASURED, device, api, score, null, null);
    }

    public static GpuBenchmarkResult unavailable(String device, String reason, String detail) {
        return new GpuBenchmarkResult(STATUS_UNAVAILABLE, device, null, null, reason, detail);
    }

    public String scoreUnit() {
        return STATUS_MEASURED.equals(status) ? SCORE_UNIT : null;
    }
}
