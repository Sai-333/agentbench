package com.agentbench.execution.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Hardened execution defaults for evaluation workers (Milestone 7+).
 *
 * <p><strong>Docker-only isolation is not production-safe for arbitrary untrusted workloads.</strong>
 * Prefer {@code mode=gvisor} on Linux hosts with runsc when stronger sandboxing is required
 * (ADR-004). Firecracker is not implemented in this codebase.
 */
@ConfigurationProperties(prefix = "agentbench.execution")
public class ExecutionProperties {

    /**
     * Isolation backend selection:
     * <ul>
     *   <li>{@code docker} — hardened Docker Engine (default; Mac Compose / trusted demos)</li>
     *   <li>{@code gvisor} — Docker {@code --runtime=runsc}; fail-fast if runsc unavailable</li>
     *   <li>{@code local} — host-process fallback (CI only; not a security boundary)</li>
     * </ul>
     */
    private String mode = "docker";

    /** Allowlisted runner image reference (tag or digest). Digest preferred. */
    private String image = "eclipse-temurin:21-jre-alpine";

    /** Optional image digest pin (sha256:...). When set, appended as image@digest. */
    private String imageDigest = "";

    private int defaultMemoryMb = 512;
    private double defaultCpuLimit = 1.0;
    private int defaultPidsLimit = 128;
    private int defaultTimeoutSec = 120;
    private boolean networkEnabledByDefault = false;
    private boolean readOnlyRootFs = true;
    private String nonRootUser = "65534:65534";
    private String tmpfsSize = "64m";
    private String dockerBinary = "docker";

    /**
     * When true, worker may call the host Docker CLI. Never mounts docker.sock into
     * evaluation containers. Compose may bind-mount the socket into the <em>worker</em> only.
     */
    private boolean dockerEnabled = true;

    /**
     * Docker runtime name registered for gVisor (typically {@code runsc}).
     * Used as {@code docker run --runtime <name>} when {@code mode=gvisor}.
     */
    private String gvisorRuntime = "runsc";

    /** Host binary used to probe gVisor availability ({@code runsc --version}). */
    private String runscBinary = "runsc";

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }

    public String getImage() {
        return image;
    }

    public void setImage(String image) {
        this.image = image;
    }

    public String getImageDigest() {
        return imageDigest;
    }

    public void setImageDigest(String imageDigest) {
        this.imageDigest = imageDigest;
    }

    public int getDefaultMemoryMb() {
        return defaultMemoryMb;
    }

    public void setDefaultMemoryMb(int defaultMemoryMb) {
        this.defaultMemoryMb = defaultMemoryMb;
    }

    public double getDefaultCpuLimit() {
        return defaultCpuLimit;
    }

    public void setDefaultCpuLimit(double defaultCpuLimit) {
        this.defaultCpuLimit = defaultCpuLimit;
    }

    public int getDefaultPidsLimit() {
        return defaultPidsLimit;
    }

    public void setDefaultPidsLimit(int defaultPidsLimit) {
        this.defaultPidsLimit = defaultPidsLimit;
    }

    public int getDefaultTimeoutSec() {
        return defaultTimeoutSec;
    }

    public void setDefaultTimeoutSec(int defaultTimeoutSec) {
        this.defaultTimeoutSec = defaultTimeoutSec;
    }

    public boolean isNetworkEnabledByDefault() {
        return networkEnabledByDefault;
    }

    public void setNetworkEnabledByDefault(boolean networkEnabledByDefault) {
        this.networkEnabledByDefault = networkEnabledByDefault;
    }

    public boolean isReadOnlyRootFs() {
        return readOnlyRootFs;
    }

    public void setReadOnlyRootFs(boolean readOnlyRootFs) {
        this.readOnlyRootFs = readOnlyRootFs;
    }

    public String getNonRootUser() {
        return nonRootUser;
    }

    public void setNonRootUser(String nonRootUser) {
        this.nonRootUser = nonRootUser;
    }

    public String getTmpfsSize() {
        return tmpfsSize;
    }

    public void setTmpfsSize(String tmpfsSize) {
        this.tmpfsSize = tmpfsSize;
    }

    public String getDockerBinary() {
        return dockerBinary;
    }

    public void setDockerBinary(String dockerBinary) {
        this.dockerBinary = dockerBinary;
    }

    public boolean isDockerEnabled() {
        return dockerEnabled;
    }

    public void setDockerEnabled(boolean dockerEnabled) {
        this.dockerEnabled = dockerEnabled;
    }

    public String getGvisorRuntime() {
        return gvisorRuntime;
    }

    public void setGvisorRuntime(String gvisorRuntime) {
        this.gvisorRuntime = gvisorRuntime;
    }

    public String getRunscBinary() {
        return runscBinary;
    }

    public void setRunscBinary(String runscBinary) {
        this.runscBinary = runscBinary;
    }

    public boolean isGvisorMode() {
        return "gvisor".equalsIgnoreCase(mode);
    }

    public boolean isLocalMode() {
        return "local".equalsIgnoreCase(mode);
    }

    public boolean isDockerMode() {
        return mode == null || mode.isBlank() || "docker".equalsIgnoreCase(mode);
    }

    public String resolvedImageRef() {
        if (imageDigest != null && !imageDigest.isBlank()) {
            String digest = imageDigest.startsWith("sha256:") ? imageDigest : "sha256:" + imageDigest;
            String base = image.contains("@") ? image.substring(0, image.indexOf('@')) : image;
            return base + "@" + digest;
        }
        return image;
    }
}
