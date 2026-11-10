package com.agentbench.execution.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Hardened execution defaults for evaluation workers (Milestone 7+).
 *
 * <p><strong>Docker-only isolation is not production-safe for arbitrary untrusted workloads.</strong>
 * Prefer {@code mode=gvisor} on Linux hosts with runsc, or {@code mode=firecracker} on Linux+KVM
 * hosts when stronger sandboxing is required (ADR-004). Firecracker is opt-in and fail-fast;
 * Mac Compose stays on Docker. Not a certified multi-tenant SaaS claim.
 */
@ConfigurationProperties(prefix = "agentbench.execution")
public class ExecutionProperties {

    /**
     * Isolation backend selection:
     * <ul>
     *   <li>{@code docker} — hardened Docker Engine (default; Mac Compose / trusted demos)</li>
     *   <li>{@code gvisor} — Docker {@code --runtime=runsc}; fail-fast if runsc unavailable</li>
     *   <li>{@code firecracker} — microVM via documented wrapper + firecracker/jailer;
     *       fail-fast without binary/KVM</li>
     *   <li>{@code local} — host-process fallback (CI only; not a security boundary)</li>
     * </ul>
     */
    private String mode = "docker";

    /**
     * Default allowlisted runner image (CODING_CLI / generic). Digest preferred via
     * {@link #imageDigest}. Language toolchains prefer {@link #javaImage}, {@link #pythonImage},
     * {@link #nodeImage} when set.
     */
    private String image = "eclipse-temurin:21-jre-alpine";

    /** Optional image digest pin (sha256:...). When set, appended as image@digest. */
    private String imageDigest = "";

    /**
     * Maven / JUnit suite runner image (JDK + Maven). Env: {@code AGENTBENCH_JAVA_RUNNER_IMAGE}.
     */
    private String javaImage = "maven:3.9.9-eclipse-temurin-21-alpine";

    /**
     * pytest suite runner image (Python + pytest). Build from {@code deploy/runners/python}.
     * Env: {@code AGENTBENCH_PYTHON_RUNNER_IMAGE}.
     */
    private String pythonImage = "agentbench/runner-python:3.12";

    /**
     * Jest suite runner image (Node + jest + jest-junit). Build from {@code deploy/runners/node}.
     * Env: {@code AGENTBENCH_NODE_RUNNER_IMAGE}.
     */
    private String nodeImage = "agentbench/runner-node:22";

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

    /**
     * Documented wrapper invoked for Firecracker runs (default {@code agentbench-fc-run}).
     * See {@code scripts/firecracker/agentbench-fc-run.sh}.
     */
    private String firecrackerWrapper = "agentbench-fc-run";

    /** Host Firecracker binary probed via {@code firecracker --version}. */
    private String firecrackerBinary = "firecracker";

    /** Optional jailer binary when {@link #firecrackerUseJailer} is true. */
    private String jailerBinary = "jailer";

    /** When true, availability also requires jailer and the wrapper is passed {@code --use-jailer}. */
    private boolean firecrackerUseJailer = false;

    /** Character device probed for KVM (typically {@code /dev/kvm}). */
    private String kvmDevice = "/dev/kvm";

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

    public String getJavaImage() {
        return javaImage;
    }

    public void setJavaImage(String javaImage) {
        this.javaImage = javaImage;
    }

    public String getPythonImage() {
        return pythonImage;
    }

    public void setPythonImage(String pythonImage) {
        this.pythonImage = pythonImage;
    }

    public String getNodeImage() {
        return nodeImage;
    }

    public void setNodeImage(String nodeImage) {
        this.nodeImage = nodeImage;
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

    public String getFirecrackerWrapper() {
        return firecrackerWrapper;
    }

    public void setFirecrackerWrapper(String firecrackerWrapper) {
        this.firecrackerWrapper = firecrackerWrapper;
    }

    public String getFirecrackerBinary() {
        return firecrackerBinary;
    }

    public void setFirecrackerBinary(String firecrackerBinary) {
        this.firecrackerBinary = firecrackerBinary;
    }

    public String getJailerBinary() {
        return jailerBinary;
    }

    public void setJailerBinary(String jailerBinary) {
        this.jailerBinary = jailerBinary;
    }

    public boolean isFirecrackerUseJailer() {
        return firecrackerUseJailer;
    }

    public void setFirecrackerUseJailer(boolean firecrackerUseJailer) {
        this.firecrackerUseJailer = firecrackerUseJailer;
    }

    public String getKvmDevice() {
        return kvmDevice;
    }

    public void setKvmDevice(String kvmDevice) {
        this.kvmDevice = kvmDevice;
    }

    public boolean isGvisorMode() {
        return "gvisor".equalsIgnoreCase(mode);
    }

    public boolean isFirecrackerMode() {
        return "firecracker".equalsIgnoreCase(mode);
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

    /** Image for Maven/JUnit suite runs (falls back to {@link #resolvedImageRef()} if blank). */
    public String resolvedJavaRunnerImage() {
        return firstNonBlank(javaImage, resolvedImageRef());
    }

    /** Image for pytest suite runs. */
    public String resolvedPythonRunnerImage() {
        return firstNonBlank(pythonImage, resolvedImageRef());
    }

    /** Image for Jest/Node suite runs. */
    public String resolvedNodeRunnerImage() {
        return firstNonBlank(nodeImage, resolvedImageRef());
    }

    private static String firstNonBlank(String primary, String fallback) {
        if (primary != null && !primary.isBlank()) {
            return primary.trim();
        }
        return fallback;
    }
}
