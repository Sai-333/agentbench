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

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Docker Engine isolator with mandatory hardening flags.
 *
 * <p>Never mounts {@code docker.sock} into workloads, never uses {@code --privileged},
 * drops all capabilities, runs as non-root, network off by default, ephemeral {@code --rm},
 * CPU/mem/pids/time limits, and optional read-only rootfs + tmpfs for /tmp.
 *
 * <p><strong>Residual risk:</strong> shared-kernel Docker breakout. Not production-safe for
 * arbitrary untrusted workloads — prefer {@code agentbench.execution.mode=gvisor} on Linux
 * hosts with runsc (ADR-004). Firecracker is not implemented.
 */
@Component
public class DockerRuntimeIsolator implements RuntimeIsolator {

    private static final Logger log = LoggerFactory.getLogger(DockerRuntimeIsolator.class);
    public static final String RUNTIME_NAME = "docker-hardened-v1";

    private final ExecutionProperties properties;
    private volatile Boolean dockerAvailable;

    public DockerRuntimeIsolator(ExecutionProperties properties) {
        this.properties = properties;
    }

    @Override
    public String runtimeName() {
        return RUNTIME_NAME;
    }

    @Override
    public boolean available() {
        if (!properties.isDockerEnabled()) {
            return false;
        }
        if (!"docker".equalsIgnoreCase(properties.getMode())) {
            return false;
        }
        Boolean cached = dockerAvailable;
        if (cached != null) {
            return cached;
        }
        synchronized (this) {
            if (dockerAvailable != null) {
                return dockerAvailable;
            }
            dockerAvailable = probeDocker();
            return dockerAvailable;
        }
    }

    @Override
    public IsolationResult run(IsolationSpec spec) {
        assertSafeSpec(spec);
        List<String> cmd = buildDockerCommand(spec);
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("runtime", RUNTIME_NAME);
        meta.put("stage", spec.stage().name());
        meta.put("image", properties.resolvedImageRef());
        meta.put("network", spec.networkEnabled() ? "allowlist-or-bridge" : "none");
        meta.put("privileged", false);
        meta.put("dockerSockMounted", false);
        meta.put("user", properties.getNonRootUser());
        meta.put("memoryMb", spec.memoryMb());
        meta.put("cpuLimit", spec.cpuLimit());
        meta.put("pidsLimit", spec.pidsLimit());
        meta.put("readOnlyRootFs", spec.readOnlyRootFs());
        meta.put("command", redactCommand(cmd));

        log.info("Starting hardened docker run name={} stage={} image={}",
                spec.runName(), spec.stage(), properties.resolvedImageRef());

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(false);
        Process process = null;
        boolean timedOut = false;
        boolean killed = false;
        int exit = -1;
        String stdout = "";
        String stderr = "";
        try {
            process = pb.start();
            ByteArrayOutputStream outBuf = new ByteArrayOutputStream();
            ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
            Thread outPump = pump(process.getInputStream(), outBuf);
            Thread errPump = pump(process.getErrorStream(), errBuf);
            outPump.start();
            errPump.start();
            boolean finished = process.waitFor(spec.timeout().toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                timedOut = true;
                killed = true;
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
                meta.put("timeoutSec", spec.timeout().toSeconds());
            } else {
                exit = process.exitValue();
            }
            outPump.join(2_000);
            errPump.join(2_000);
            stdout = truncate(SecretRedactor.redact(outBuf.toString(StandardCharsets.UTF_8)));
            stderr = truncate(SecretRedactor.redact(errBuf.toString(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            killed = true;
            stderr = truncate(SecretRedactor.sanitizeError(ex.getMessage()));
            meta.put("error", "docker_exec_failed");
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
                killed = true;
            }
            // Ephemeral: also force-remove by name in case --rm did not run.
            cleanup(spec.runName());
        }
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
            ProcessBuilder rm = new ProcessBuilder(
                    properties.getDockerBinary(), "rm", "-f", runNamePrefix);
            rm.redirectErrorStream(true);
            Process p = rm.start();
            p.waitFor(10, TimeUnit.SECONDS);
            if (p.isAlive()) {
                p.destroyForcibly();
            }
        } catch (Exception ex) {
            log.debug("Docker cleanup for {} failed: {}", runNamePrefix,
                    SecretRedactor.sanitizeError(ex.getMessage()));
        }
    }

    public List<String> buildDockerCommand(IsolationSpec spec) {
        List<String> cmd = new ArrayList<>();
        cmd.add(properties.getDockerBinary());
        cmd.add("run");
        cmd.add("--rm");
        cmd.add("--name");
        cmd.add(sanitizeName(spec.runName()));
        // Hardening — never privileged, never docker.sock
        cmd.add("--network");
        cmd.add(spec.networkEnabled() ? "bridge" : "none");
        cmd.add("--user");
        cmd.add(properties.getNonRootUser());
        cmd.add("--cap-drop");
        cmd.add("ALL");
        cmd.add("--security-opt");
        cmd.add("no-new-privileges:true");
        cmd.add("--memory");
        cmd.add(spec.memoryMb() + "m");
        cmd.add("--cpus");
        cmd.add(String.format(Locale.ROOT, "%.2f", spec.cpuLimit()));
        cmd.add("--pids-limit");
        cmd.add(String.valueOf(spec.pidsLimit()));
        cmd.add("--ulimit");
        cmd.add("nofile=1024:1024");
        if (spec.readOnlyRootFs()) {
            cmd.add("--read-only");
            cmd.add("--tmpfs");
            cmd.add("/tmp:rw,noexec,nosuid,size=" + properties.getTmpfsSize());
        }
        // Workspace mount — writable for agent/tests; quality may still need writes under /workspace
        String wsMode = spec.stage() == ExecutionStage.QUALITY ? "rw" : "rw";
        cmd.add("-v");
        cmd.add(spec.workspace().toAbsolutePath() + ":/workspace:" + wsMode);
        if (spec.stage() == ExecutionStage.HIDDEN_TESTS && spec.hiddenFixturesDir() != null) {
            cmd.add("-v");
            cmd.add(spec.hiddenFixturesDir().toAbsolutePath() + ":/opt/agentbench/hidden:ro");
        }
        cmd.add("-w");
        cmd.add("/workspace");
        for (Map.Entry<String, String> env : spec.env().entrySet()) {
            if (looksLikeSecretEnv(env.getKey())) {
                continue; // never inject secret-looking keys into eval containers
            }
            cmd.add("-e");
            cmd.add(env.getKey() + "=" + env.getValue());
        }
        cmd.add(properties.resolvedImageRef());
        cmd.addAll(spec.command());
        return cmd;
    }

    public static void assertSafeSpec(IsolationSpec spec) {
        for (String part : spec.command()) {
            String lower = part.toLowerCase(Locale.ROOT);
            if (lower.contains("docker.sock") || lower.contains("--privileged")) {
                throw new IllegalArgumentException("Rejected unsafe isolation command fragment");
            }
        }
        if (spec.hiddenFixturesDir() != null && spec.stage() != ExecutionStage.HIDDEN_TESTS) {
            throw new IllegalArgumentException("Hidden fixtures may only mount during HIDDEN_TESTS");
        }
    }

    private boolean probeDocker() {
        try {
            ProcessBuilder pb = new ProcessBuilder(properties.getDockerBinary(), "version", "--format", "{{.Server.Version}}");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            boolean finished = p.waitFor(5, TimeUnit.SECONDS);
            if (!finished) {
                p.destroyForcibly();
                return false;
            }
            return p.exitValue() == 0;
        } catch (Exception ex) {
            log.info("Docker probe failed; falling back to local isolator: {}",
                    SecretRedactor.sanitizeError(ex.getMessage()));
            return false;
        }
    }

    private static Thread pump(InputStream in, ByteArrayOutputStream out) {
        return new Thread(() -> {
            try {
                in.transferTo(out);
            } catch (Exception ignored) {
                // process closed
            }
        }, "docker-isolator-pump");
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

    private static List<String> redactCommand(List<String> cmd) {
        List<String> out = new ArrayList<>(cmd.size());
        for (String part : cmd) {
            out.add(SecretRedactor.redact(part));
        }
        return out;
    }

    private static String truncate(String value) {
        if (value == null) {
            return "";
        }
        int max = 200_000;
        if (value.length() <= max) {
            return value;
        }
        return value.substring(0, max) + "…[truncated]";
    }

    /** Helper for tests — builds a default timeout from properties. */
    public Duration defaultTimeout() {
        return Duration.ofSeconds(properties.getDefaultTimeoutSec());
    }

    public boolean workspaceExists(Path workspace) {
        return workspace != null && Files.isDirectory(workspace);
    }
}
