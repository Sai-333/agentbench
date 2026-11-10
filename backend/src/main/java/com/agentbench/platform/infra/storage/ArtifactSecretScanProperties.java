package com.agentbench.platform.infra.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * Config for secret scanning on artifact persist (threat-model R7).
 *
 * <p>This is pattern-based redaction/quarantine — not a full DLP product.
 */
@ConfigurationProperties(prefix = "agentbench.artifacts.secret-scan")
public class ArtifactSecretScanProperties {

    /** When false, content passes through unchanged and no audit rows are written. */
    private boolean enabled = true;

    /**
     * {@code REDACT} stores redacted bytes; {@code QUARANTINE} refuses persist when findings exist.
     */
    private String mode = "REDACT";

    /** Optional extra Java regexes (whole-match replacement with {@code [REDACTED]}). */
    private List<String> additionalPatterns = new ArrayList<>();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }

    public boolean isQuarantineMode() {
        return mode != null && "QUARANTINE".equalsIgnoreCase(mode.trim());
    }

    public List<String> getAdditionalPatterns() {
        return additionalPatterns;
    }

    public void setAdditionalPatterns(List<String> additionalPatterns) {
        this.additionalPatterns = additionalPatterns == null ? new ArrayList<>() : additionalPatterns;
    }
}
