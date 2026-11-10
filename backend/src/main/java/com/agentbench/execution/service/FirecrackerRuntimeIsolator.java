package com.agentbench.execution.service;

import com.agentbench.agents.infra.SecretRedactor;
import com.agentbench.execution.config.ExecutionProperties;
import com.agentbench.execution.domain.ExecutionStage;
import com.agentbench.execution.domain.IsolationResult;
import com.agentbench.execution.domain.IsolationSpec;
import com.agentbench.execution.domain.RuntimeIsolator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Firecracker microVM isolator via a documented host wrapper ({@code agentbench-fc-run})
 * that drives the {@code firecracker} binary and optional {@code jailer}.
 *
 * <p>Selected only when {@code agentbench.execution.mode=firecracker}. Fail-fast when the
 * Firecracker binary and/or {@code /dev/kvm} (or configured KVM device) are unavailable —
 * does <strong>not</strong> silently fall back to Docker, gVisor, or local.
 *
 * <p>Mac / Docker Desktop Compose stays on {@code mode=docker}. Live microVM execution
 * requires a Linux host with KVM. CI without Firecracker/KVM must keep the default mode;
 * unit tests mock {@link HostCommandExecutor} and never claim a live microVM without probes.
 * Reference wrapper: {@code scripts/firecracker/agentbench-fc-run.sh}.
 */
@Component
public class FirecrackerRuntimeIsolator implements RuntimeIsolator {

    private static final Logger log = LoggerFactory.getLogger(FirecrackerRuntimeIsolator.class);
    public static final String RUNTIME_NAME = "firecracker-microvm-v1";

    private final ExecutionProperties properties;
    private final HostCommandExecutor commands;
    private volatile Boolean firecrackerAvailable;

    public FirecrackerRuntimeIsolator(ExecutionProperties properties, HostCommandExecutor commands) {
        this.properties = properties;
        this.commands = commands;
    }

    @Override
    public String runtimeName() {
        return RUNTIME_NAME;
    }

    @Override
    public boolean available() {
        if (!"firecracker".equalsIgnoreCase(properties.getMode())) {
            return false;
        }
        Boolean cached = firecrackerAvailable;
        if (cached != null) {
            return cached;
        }
        synchronized (this) {
            if (firecrackerAvailable != null) {
                return firecrackerAvailable;
            }
            firecrackerAvailable = probeFirecracker() && probeKvm() && probeWrapper()
                    && (!properties.isFirecrackerUseJailer() || probeJailer());
            return firecrackerAvailable;
        }
    }

    /** Clears the availability cache (tests). */
    public void resetAvailabilityCache() {
        synchronized (this) {
            firecrackerAvailable = null;
        }
    }

    @Override
    public IsolationResult run(IsolationSpec spec) {
        if (!available()) {
            throw new IllegalStateException(unavailableMessage());
        }
        DockerRuntimeIsolator.assertSafeSpec(spec);
        List<String> cmd = buildFirecrackerCommand(spec);
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("runtime", RUNTIME_NAME);
        meta.put("wrapper", properties.getFirecrackerWrapper());
        meta.put("firecrackerBinary", properties.getFirecrackerBinary());
        meta.put("jailerBinary", properties.getJailerBinary());
        meta.put("useJailer", properties.isFirecrackerUseJailer());
        meta.put("kvmDevice", properties.getKvmDevice());
        meta.put("stage", spec.stage().name());
        meta.put("image", resolveImage(spec));
        meta.put("network", spec.networkEnabled() ? "allowlist-or-bridge" : "none");
        meta.put("privileged", false);
        meta.put("dockerSockMounted", false);
        meta.put("user", properties.getNonRootUser());
        meta.put("memoryMb", spec.memoryMb());
        meta.put("cpuLimit", spec.cpuLimit());
        meta.put("pidsLimit", spec.pidsLimit());
        meta.put("readOnlyRootFs", spec.readOnlyRootFs());
        meta.put("command", redactCommand(cmd));

        log.info("Starting Firecracker microVM via wrapper name={} stage={} wrapper={}",
                spec.runName(), spec.stage(), properties.getFirecrackerWrapper());

        HostCommandExecutor.Result result = commands.run(cmd, spec.timeout());
        boolean timedOut = result.timedOut();
        boolean killed = timedOut || !result.started();
        int exit = result.started() && !timedOut ? result.exitCode() : -1;
        String stdout = SecretRedactor.redact(result.stdout());
        String stderr = SecretRedactor.redact(result.stderr());
        if (!result.started()) {
            meta.put("error", "firecracker_exec_failed");
            killed = true;
        }
        cleanup(spec.runName());
        meta.put("timedOut", timedOut);
        meta.put("killed", killed);
        return new IsolationResult(exit, stdout, stderr, timedOut, killed, RUNTIME_NAME, meta);
    }

    @Override
    public void cleanup(String runNamePrefix) {
        if (runNamePrefix == null || runNamePrefix.isBlank()) {
            return;
        }
        try {
            commands.run(
                    List.of(properties.getFirecrackerWrapper(), "cleanup", "--name", sanitizeName(runNamePrefix)),
                    Duration.ofSeconds(15));
        } catch (Exception ex) {
            log.debug("Firecracker cleanup for {} failed: {}", runNamePrefix,
                    SecretRedactor.sanitizeError(ex.getMessage()));
        }
    }

    /**
     * Builds the documented wrapper argv. Operators provide {@code agentbench-fc-run}
     * (see {@code scripts/firecracker/agentbench-fc-run.sh}) on {@code PATH}, or override
     * {@code agentbench.execution.firecracker-wrapper}.
     */
    public List<String> buildFirecrackerCommand(IsolationSpec spec) {
        List<String> cmd = new ArrayList<>();
        cmd.add(properties.getFirecrackerWrapper());
        cmd.add("run");
        cmd.add("--name");
        cmd.add(sanitizeName(spec.runName()));
        cmd.add("--workspace");
        cmd.add(spec.workspace().toAbsolutePath().toString());
        cmd.add("--memory-mb");
        cmd.add(String.valueOf(spec.memoryMb()));
        cmd.add("--cpus");
        cmd.add(String.format(Locale.ROOT, "%.2f", spec.cpuLimit()));
        cmd.add("--pids-limit");
        cmd.add(String.valueOf(spec.pidsLimit()));
        cmd.add("--network");
        cmd.add(spec.networkEnabled() ? "bridge" : "none");
        cmd.add("--timeout-sec");
        cmd.add(String.valueOf(Math.max(1, spec.timeout().toSeconds())));
        cmd.add("--user");
        cmd.add(properties.getNonRootUser());
        cmd.add("--firecracker");
        cmd.add(properties.getFirecrackerBinary());
        cmd.add("--kvm-device");
        cmd.add(properties.getKvmDevice());
        if (properties.isFirecrackerUseJailer()) {
            cmd.add("--use-jailer");
            cmd.add("--jailer");
            cmd.add(properties.getJailerBinary());
        }
        if (spec.stage() == ExecutionStage.HIDDEN_TESTS && spec.hiddenFixturesDir() != null) {
            cmd.add("--hidden-fixtures");
            cmd.add(spec.hiddenFixturesDir().toAbsolutePath().toString());
        }
        if (spec.readOnlyRootFs()) {
            cmd.add("--read-only-rootfs");
        }
        cmd.add("--image");
        cmd.add(resolveImage(spec));
        for (Map.Entry<String, String> env : spec.env().entrySet()) {
            if (looksLikeSecretEnv(env.getKey())) {
                continue;
            }
            cmd.add("--env");
            cmd.add(env.getKey() + "=" + env.getValue());
        }
        cmd.add("--");
        cmd.addAll(spec.command());
        return cmd;
    }

    public String resolveImage(IsolationSpec spec) {
        if (spec.imageOverride() != null && !spec.imageOverride().isBlank()) {
            return spec.imageOverride().trim();
        }
        return properties.resolvedImageRef();
    }

    public static String unavailableMessage() {
        return "Firecracker mode requested (agentbench.execution.mode=firecracker) but the "
                + "firecracker binary, KVM device, and/or documented wrapper are unavailable. "
                + "Install Firecracker on a Linux host with /dev/kvm (see scripts/firecracker/), "
                + "or set AGENTBENCH_EXECUTION_MODE=docker (Mac/Compose default), gvisor, or local (CI). "
                + "Do not claim a live microVM without KVM+Firecracker detection.";
    }

    private boolean probeFirecracker() {
        HostCommandExecutor.Result result = commands.run(
                List.of(properties.getFirecrackerBinary(), "--version"),
                Duration.ofSeconds(5));
        if (!result.started() || result.timedOut() || result.exitCode() != 0) {
            log.info("Firecracker mode: firecracker probe failed (binary={}): {}",
                    properties.getFirecrackerBinary(),
                    SecretRedactor.sanitizeError(blankTo(result.stderr(), result.stdout())));
            return false;
        }
        return true;
    }

    private boolean probeKvm() {
        String device = properties.getKvmDevice();
        HostCommandExecutor.Result result = commands.run(
                List.of("test", "-c", device),
                Duration.ofSeconds(3));
        if (!result.started() || result.timedOut() || result.exitCode() != 0) {
            log.info("Firecracker mode: KVM probe failed (device={}): {}",
                    device,
                    SecretRedactor.sanitizeError(blankTo(result.stderr(), result.stdout())));
            return false;
        }
        return true;
    }

    private boolean probeWrapper() {
        HostCommandExecutor.Result result = commands.run(
                List.of(properties.getFirecrackerWrapper(), "--help"),
                Duration.ofSeconds(5));
        if (!result.started() || result.timedOut()) {
            log.info("Firecracker mode: wrapper probe failed (wrapper={}): {}",
                    properties.getFirecrackerWrapper(),
                    SecretRedactor.sanitizeError(blankTo(result.stderr(), result.stdout())));
            return false;
        }
        // --help may exit 0 or 1 depending on the script; started + not timed out is enough.
        String blob = (result.stdout() + " " + result.stderr()).toLowerCase(Locale.ROOT);
        if (blob.contains("agentbench") || blob.contains("firecracker") || blob.contains("usage")
                || result.exitCode() == 0) {
            return true;
        }
        log.info("Firecracker mode: wrapper probe returned unexpected output (wrapper={})",
                properties.getFirecrackerWrapper());
        return false;
    }

    private boolean probeJailer() {
        HostCommandExecutor.Result result = commands.run(
                List.of(properties.getJailerBinary(), "--version"),
                Duration.ofSeconds(5));
        if (!result.started() || result.timedOut() || result.exitCode() != 0) {
            // jailer --version is not universal; accept --help as a presence check.
            HostCommandExecutor.Result help = commands.run(
                    List.of(properties.getJailerBinary(), "--help"),
                    Duration.ofSeconds(5));
            if (help.started() && !help.timedOut()) {
                return true;
            }
            log.info("Firecracker mode: jailer probe failed (binary={}): {}",
                    properties.getJailerBinary(),
                    SecretRedactor.sanitizeError(blankTo(result.stderr(), result.stdout())));
            return false;
        }
        return true;
    }

    private static String sanitizeName(String name) {
        String cleaned = name.replaceAll("[^a-zA-Z0-9_.-]", "-");
        if (cleaned.length() > 60) {
            cleaned = cleaned.substring(0, 60);
        }
        return cleaned;
    }

    private static boolean looksLikeSecretEnv(String key) {
        if (key == null) {
            return true;
        }
        String n = key.toLowerCase(Locale.ROOT);
        return n.contains("secret") || n.contains("password") || n.contains("token")
                || n.contains("api_key") || n.contains("apikey") || n.contains("authorization");
    }

    private static String blankTo(String primary, String fallback) {
        if (primary != null && !primary.isBlank()) {
            return primary;
        }
        return fallback == null ? "" : fallback;
    }

    private static List<String> redactCommand(List<String> cmd) {
        List<String> out = new ArrayList<>(cmd.size());
        for (String part : cmd) {
            out.add(SecretRedactor.redact(part));
        }
        return out;
    }
}
