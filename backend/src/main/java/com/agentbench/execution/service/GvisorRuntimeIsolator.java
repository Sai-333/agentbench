package com.agentbench.execution.service;

import com.agentbench.agents.infra.SecretRedactor;
import com.agentbench.execution.config.ExecutionProperties;
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
 * gVisor isolator via Docker's {@code --runtime=runsc} (or configured runtime name).
 *
 * <p>Selected only when {@code agentbench.execution.mode=gvisor}. Fail-fast when Docker or
 * {@code runsc} is unavailable — does <strong>not</strong> silently fall back to Docker/local.
 *
 * <p>Mac / Docker Desktop: keep {@code mode=docker}. gVisor requires a Linux host with the
 * runsc runtime installed and registered with the Docker engine.
 */
@Component
public class GvisorRuntimeIsolator implements RuntimeIsolator {

    private static final Logger log = LoggerFactory.getLogger(GvisorRuntimeIsolator.class);
    public static final String RUNTIME_NAME = "gvisor-runsc-v1";

    private final ExecutionProperties properties;
    private final DockerRuntimeIsolator dockerIsolator;
    private final HostCommandExecutor commands;
    private volatile Boolean gvisorAvailable;

    public GvisorRuntimeIsolator(
            ExecutionProperties properties,
            DockerRuntimeIsolator dockerIsolator,
            HostCommandExecutor commands) {
        this.properties = properties;
        this.dockerIsolator = dockerIsolator;
        this.commands = commands;
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
        if (!"gvisor".equalsIgnoreCase(properties.getMode())) {
            return false;
        }
        Boolean cached = gvisorAvailable;
        if (cached != null) {
            return cached;
        }
        synchronized (this) {
            if (gvisorAvailable != null) {
                return gvisorAvailable;
            }
            gvisorAvailable = probeDocker() && probeRunsc();
            return gvisorAvailable;
        }
    }

    /**
     * Clears the availability cache (tests).
     */
    public void resetAvailabilityCache() {
        synchronized (this) {
            gvisorAvailable = null;
        }
    }

    @Override
    public IsolationResult run(IsolationSpec spec) {
        if (!available()) {
            throw new IllegalStateException(unavailableMessage());
        }
        DockerRuntimeIsolator.assertSafeSpec(spec);
        List<String> cmd = buildGvisorCommand(spec);
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("runtime", RUNTIME_NAME);
        meta.put("dockerRuntime", properties.getGvisorRuntime());
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

        log.info("Starting gVisor (runsc) docker run name={} stage={} image={} runtime={}",
                spec.runName(), spec.stage(), properties.resolvedImageRef(), properties.getGvisorRuntime());

        HostCommandExecutor.Result result = commands.run(cmd, spec.timeout());
        boolean timedOut = result.timedOut();
        boolean killed = timedOut || !result.started();
        int exit = result.started() && !timedOut ? result.exitCode() : -1;
        String stdout = SecretRedactor.redact(result.stdout());
        String stderr = SecretRedactor.redact(result.stderr());
        if (!result.started()) {
            meta.put("error", "gvisor_exec_failed");
            killed = true;
        }
        // Ephemeral: also force-remove by name in case --rm did not run.
        cleanup(spec.runName());
        meta.put("timedOut", timedOut);
        meta.put("killed", killed);
        return new IsolationResult(exit, stdout, stderr, timedOut, killed, RUNTIME_NAME, meta);
    }

    @Override
    public void cleanup(String runNamePrefix) {
        dockerIsolator.cleanup(runNamePrefix);
    }

    public List<String> buildGvisorCommand(IsolationSpec spec) {
        List<String> dockerCmd = dockerIsolator.buildDockerCommand(spec);
        List<String> cmd = new ArrayList<>(dockerCmd.size() + 2);
        // docker run --runtime <name> ...
        if (dockerCmd.size() < 2 || !"run".equals(dockerCmd.get(1))) {
            throw new IllegalStateException("Unexpected docker command shape; expected '<docker> run ...'");
        }
        cmd.add(dockerCmd.get(0));
        cmd.add("run");
        cmd.add("--runtime");
        cmd.add(properties.getGvisorRuntime());
        cmd.addAll(dockerCmd.subList(2, dockerCmd.size()));
        return cmd;
    }

    public static String unavailableMessage() {
        return "gVisor mode requested (agentbench.execution.mode=gvisor) but Docker and/or runsc "
                + "are unavailable. Install the gVisor runsc runtime on a Linux Docker host, or set "
                + "AGENTBENCH_EXECUTION_MODE=docker (Mac/Compose default) or local (CI).";
    }

    private boolean probeDocker() {
        HostCommandExecutor.Result result = commands.run(
                List.of(properties.getDockerBinary(), "version", "--format", "{{.Server.Version}}"),
                Duration.ofSeconds(5));
        if (!result.started() || result.timedOut() || result.exitCode() != 0) {
            log.info("gVisor mode: Docker probe failed: {}",
                    SecretRedactor.sanitizeError(blankTo(result.stderr(), result.stdout())));
            return false;
        }
        return true;
    }

    private boolean probeRunsc() {
        // Prefer direct runsc binary (works even before docker runtime registration checks).
        HostCommandExecutor.Result runsc = commands.run(
                List.of(properties.getRunscBinary(), "--version"),
                Duration.ofSeconds(5));
        if (runsc.started() && !runsc.timedOut() && runsc.exitCode() == 0) {
            return true;
        }
        // Fallback: Docker reports the configured runtime name.
        HostCommandExecutor.Result info = commands.run(
                List.of(properties.getDockerBinary(), "info", "--format", "{{json .Runtimes}}"),
                Duration.ofSeconds(5));
        if (info.started() && !info.timedOut() && info.exitCode() == 0) {
            String blob = (info.stdout() + " " + info.stderr()).toLowerCase(Locale.ROOT);
            String runtime = properties.getGvisorRuntime().toLowerCase(Locale.ROOT);
            if (blob.contains("\"" + runtime + "\"") || blob.contains(runtime)) {
                return true;
            }
        }
        log.info("gVisor mode: runsc probe failed (binary={} runtime={}): {}",
                properties.getRunscBinary(),
                properties.getGvisorRuntime(),
                SecretRedactor.sanitizeError(blankTo(runsc.stderr(), runsc.stdout())));
        return false;
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
