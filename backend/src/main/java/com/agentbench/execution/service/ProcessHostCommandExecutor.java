package com.agentbench.execution.service;

import com.agentbench.agents.infra.SecretRedactor;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Default {@link HostCommandExecutor} using {@link ProcessBuilder}.
 */
@Component
public class ProcessHostCommandExecutor implements HostCommandExecutor {

    @Override
    public Result run(List<String> command, Duration timeout) {
        if (command == null || command.isEmpty()) {
            return Result.failedToStart("command required");
        }
        Process process = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(false);
            process = pb.start();
            ByteArrayOutputStream outBuf = new ByteArrayOutputStream();
            ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
            Thread outPump = pump(process.getInputStream(), outBuf);
            Thread errPump = pump(process.getErrorStream(), errBuf);
            outPump.start();
            errPump.start();
            long waitMs = timeout == null ? 5_000L : Math.max(1L, timeout.toMillis());
            boolean finished = process.waitFor(waitMs, TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
                outPump.join(2_000);
                errPump.join(2_000);
                return new Result(-1,
                        truncate(outBuf.toString(StandardCharsets.UTF_8)),
                        truncate(errBuf.toString(StandardCharsets.UTF_8)),
                        true,
                        true);
            }
            outPump.join(2_000);
            errPump.join(2_000);
            return new Result(
                    process.exitValue(),
                    truncate(outBuf.toString(StandardCharsets.UTF_8)),
                    truncate(errBuf.toString(StandardCharsets.UTF_8)),
                    false,
                    true);
        } catch (Exception ex) {
            return Result.failedToStart(SecretRedactor.sanitizeError(ex.getMessage()));
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    private static Thread pump(InputStream in, ByteArrayOutputStream out) {
        return new Thread(() -> {
            try {
                in.transferTo(out);
            } catch (Exception ignored) {
                // process closed
            }
        }, "host-command-pump");
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
}
