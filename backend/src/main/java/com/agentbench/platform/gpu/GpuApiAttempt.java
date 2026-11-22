package com.agentbench.platform.gpu;

import java.math.BigDecimal;

/**
 * Outcome of one API probe. {@code MEASURED} is the only kind that carries a score.
 */
final class GpuApiAttempt {

    enum Kind {
        ABSENT,
        NO_DEVICE,
        FAILED,
        MEASURED
    }

    private final Kind kind;
    private final String api;
    private final String device;
    private final BigDecimal score;
    private final String detail;

    private GpuApiAttempt(Kind kind, String api, String device, BigDecimal score, String detail) {
        this.kind = kind;
        this.api = api;
        this.device = device;
        this.score = score;
        this.detail = detail;
    }

    static GpuApiAttempt absent(String api) {
        return new GpuApiAttempt(Kind.ABSENT, api, null, null, null);
    }

    static GpuApiAttempt noDevice(String api) {
        return new GpuApiAttempt(Kind.NO_DEVICE, api, null, null, null);
    }

    static GpuApiAttempt failed(String api, String device, String detail) {
        return new GpuApiAttempt(Kind.FAILED, api, device, null, detail);
    }

    static GpuApiAttempt measured(String api, String device, BigDecimal score) {
        return new GpuApiAttempt(Kind.MEASURED, api, device, score, null);
    }

    Kind kind() {
        return kind;
    }

    String api() {
        return api;
    }

    String device() {
        return device;
    }

    BigDecimal score() {
        return score;
    }

    String detail() {
        return detail;
    }
}
