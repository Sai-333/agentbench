package com.agentbench.platform.gpu;

import java.math.BigDecimal;

/**
 * Outcome of one graphics probe attempt. {@code MEASURED} is the only kind
 * that carries a frame time.
 */
final class GraphicsAttempt {

    enum Kind {
        ABSENT,
        NO_DEVICE,
        FAILED,
        MEASURED
    }

    private final Kind kind;
    private final String device;
    private final Integer frames;
    private final BigDecimal frameTimeMs;
    private final String detail;

    private GraphicsAttempt(
            Kind kind, String device, Integer frames, BigDecimal frameTimeMs, String detail) {
        this.kind = kind;
        this.device = device;
        this.frames = frames;
        this.frameTimeMs = frameTimeMs;
        this.detail = detail;
    }

    static GraphicsAttempt absent() {
        return new GraphicsAttempt(Kind.ABSENT, null, null, null, null);
    }

    static GraphicsAttempt noDevice() {
        return new GraphicsAttempt(Kind.NO_DEVICE, null, null, null, null);
    }

    static GraphicsAttempt failed(String device, String detail) {
        return new GraphicsAttempt(Kind.FAILED, device, null, null, detail);
    }

    static GraphicsAttempt measured(String device, int frames, BigDecimal frameTimeMs) {
        return new GraphicsAttempt(Kind.MEASURED, device, frames, frameTimeMs, null);
    }

    Kind kind() {
        return kind;
    }

    String device() {
        return device;
    }

    Integer frames() {
        return frames;
    }

    BigDecimal frameTimeMs() {
        return frameTimeMs;
    }

    String detail() {
        return detail;
    }
}
