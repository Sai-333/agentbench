package com.agentbench.execution.service;

import com.agentbench.agents.infra.SecretRedactor;
import com.agentbench.execution.domain.IsolationResult;
import com.agentbench.execution.domain.IsolationSpec;
import com.agentbench.execution.domain.RuntimeIsolator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Host-process fallback when Docker is unavailable (unit tests / local CI without daemon).
 * Still enforces timeout and refuses docker.sock / privileged command fragments.
 * Does <strong>not</strong> provide production isolation — Docker/gVisor required for untrusted agents.
 */
@Component
public class LocalRuntimeIsolator implements RuntimeIsolator {

    private static final Logger log = LoggerFactory.getLogger(LocalRuntimeIsolator.class);
    public static final String RUNTIME_NAME = "local-process-v1";

    @Override
    public String runtimeName() {
        return RUNTIME_NAME;
    }

    @Override
    public boolean available() {
        return true;
    }

    @Override
    public IsolationResult run(IsolationSpec spec) {
        DockerRuntimeIsolator.assertSafeSpec(spec);
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("runtime", RUNTIME_NAME);
        meta.put("stage", spec.stage().name());
        meta.put("warning", "local_fallback_not_production_safe");
        meta.put("privileged", false);
        meta.put("dockerSockMounted", false);

        ProcessBuilder pb = new ProcessBuilder(spec.command());
        pb.directory(spec.workspace().toFile());
        pb.environment().keySet().removeIf(k ->
                k.toLowerCase().contains("secret")
                        || k.toLowerCase().contains("password")
                        || k.toLowerCase().contains("token")
                        || k.toLowerCase().contains("api_key"));
        for (Map.Entry<String, String> env : spec.env().entrySet()) {
            pb.environment().put(env.getKey(), env.getValue());
        }

        boolean timedOut = false;
        boolean killed = false;
        int exit = -1;
        String stdout = "";
        String stderr = "";
        Process started = null;
        try {
            final Process process = pb.start();
            started = process;
            ByteArrayOutputStream outBuf = new ByteArrayOutputStream();
            ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
            Thread outPump = new Thread(() -> {
                try {
                    process.getInputStream().transferTo(outBuf);
                } catch (Exception ignored) {
                }
            });
            Thread errPump = new Thread(() -> {
                try {
                    process.getErrorStream().transferTo(errBuf);
                } catch (Exception ignored) {
                }
            });
            outPump.start();
            errPump.start();
            boolean finished = process.waitFor(spec.timeout().toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                timedOut = true;
                killed = true;
                process.destroyForcibly();
            } else {
                exit = process.exitValue();
            }
            outPump.join(1_000);
            errPump.join(1_000);
            stdout = SecretRedactor.redact(outBuf.toString(StandardCharsets.UTF_8));
            stderr = SecretRedactor.redact(errBuf.toString(StandardCharsets.UTF_8));
        } catch (Exception ex) {
            killed = true;
            stderr = SecretRedactor.sanitizeError(ex.getMessage());
            log.warn("Local isolator failed: {}", stderr);
        } finally {
            if (started != null && started.isAlive()) {
                started.destroyForcibly();
                killed = true;
            }
        }
        return new IsolationResult(exit, stdout, stderr, timedOut, killed, RUNTIME_NAME, meta);
    }

    @Override
    public void cleanup(String runNamePrefix) {
        // No ephemeral containers in local mode.
    }
}
