package com.agentbench.execution.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "agentbench.artifacts.retention")
public class ArtifactRetentionProperties {

    /** Keep evaluation artifacts for this many days (0 = never auto-delete). */
    private int days = 14;

    /** Maximum log / report artifact size in bytes before truncation at store time. */
    private long maxLogBytes = 20_971_520L;

    /** How often the cleanup sweep runs (ms). */
    private long sweepIntervalMs = 3_600_000L;

    /** When false, sweeps are logged but do not delete. */
    private boolean enabled = true;

    public int getDays() {
        return days;
    }

    public void setDays(int days) {
        this.days = days;
    }

    public long getMaxLogBytes() {
        return maxLogBytes;
    }

    public void setMaxLogBytes(long maxLogBytes) {
        this.maxLogBytes = maxLogBytes;
    }

    public long getSweepIntervalMs() {
        return sweepIntervalMs;
    }

    public void setSweepIntervalMs(long sweepIntervalMs) {
        this.sweepIntervalMs = sweepIntervalMs;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
}
