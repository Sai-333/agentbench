package com.agentbench.execution;

import com.agentbench.execution.config.ExecutionProperties;
import com.agentbench.execution.domain.ExecutionStage;
import com.agentbench.execution.domain.IsolationResult;
import com.agentbench.execution.domain.IsolationSpec;
import com.agentbench.execution.service.DockerRuntimeIsolator;
import com.agentbench.execution.service.GvisorRuntimeIsolator;
import com.agentbench.execution.service.HostCommandExecutor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GvisorRuntimeIsolatorTest {

    @TempDir
    Path temp;

    private ExecutionProperties props;
    private DockerRuntimeIsolator dockerIsolator;
    private RecordingExecutor executor;
    private GvisorRuntimeIsolator isolator;

    @BeforeEach
    void setUp() {
        props = new ExecutionProperties();
        props.setMode("gvisor");
        props.setDockerEnabled(true);
        props.setImage("eclipse-temurin:21-jre-alpine");
        props.setGvisorRuntime("runsc");
        props.setRunscBinary("runsc");
        dockerIsolator = new DockerRuntimeIsolator(props);
        executor = new RecordingExecutor();
        isolator = new GvisorRuntimeIsolator(props, dockerIsolator, executor);
    }

    @Test
    void buildCommandInsertsRuntimeRunscAndKeepsHardening() {
        IsolationSpec spec = sampleSpec();
        List<String> cmd = isolator.buildGvisorCommand(spec);
        String joined = String.join(" ", cmd);
        assertTrue(joined.startsWith("docker run --runtime runsc"));
        assertTrue(joined.contains("--network none"));
        assertTrue(joined.contains("--cap-drop ALL"));
        assertTrue(joined.contains("--security-opt no-new-privileges:true"));
        assertFalse(joined.contains("docker.sock"));
        assertFalse(joined.contains("--privileged"));
    }

    @Test
    void availableWhenDockerAndRunscProbesSucceed() {
        executor.enqueue(new HostCommandExecutor.Result(0, "24.0.0", "", false, true)); // docker version
        executor.enqueue(new HostCommandExecutor.Result(0, "runsc version release-202411", "", false, true));
        assertTrue(isolator.available());
        assertEquals(List.of("docker", "version", "--format", "{{.Server.Version}}"), executor.commands.get(0));
        assertEquals(List.of("runsc", "--version"), executor.commands.get(1));
    }

    @Test
    void availableViaDockerRuntimesWhenRunscBinaryMissing() {
        executor.enqueue(new HostCommandExecutor.Result(0, "24.0.0", "", false, true)); // docker version
        executor.enqueue(HostCommandExecutor.Result.failedToStart("runsc: not found"));
        executor.enqueue(new HostCommandExecutor.Result(0, "{\"runc\":{},\"runsc\":{}}", "", false, true));
        assertTrue(isolator.available());
    }

    @Test
    void unavailableWhenRunscMissing() {
        executor.enqueue(new HostCommandExecutor.Result(0, "24.0.0", "", false, true));
        executor.enqueue(HostCommandExecutor.Result.failedToStart("runsc: not found"));
        executor.enqueue(new HostCommandExecutor.Result(0, "{\"runc\":{}}", "", false, true));
        assertFalse(isolator.available());
    }

    @Test
    void unavailableWhenModeIsDocker() {
        props.setMode("docker");
        isolator.resetAvailabilityCache();
        assertFalse(isolator.available());
        assertTrue(executor.commands.isEmpty());
    }

    @Test
    void runFailsFastWhenUnavailable() {
        props.setMode("gvisor");
        executor.enqueue(HostCommandExecutor.Result.failedToStart("docker: not found"));
        isolator.resetAvailabilityCache();
        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> isolator.run(sampleSpec()));
        assertTrue(ex.getMessage().contains("gVisor mode requested"));
        assertTrue(ex.getMessage().contains("AGENTBENCH_EXECUTION_MODE=docker"));
    }

    @Test
    void runUsesGvisorCommandWhenAvailable() {
        executor.enqueue(new HostCommandExecutor.Result(0, "24.0.0", "", false, true)); // probe docker
        executor.enqueue(new HostCommandExecutor.Result(0, "runsc ok", "", false, true)); // probe runsc
        executor.onMatch(cmd -> cmd.size() > 2 && "run".equals(cmd.get(1)),
                new HostCommandExecutor.Result(0, "hello", "", false, true));
        // cleanup docker rm - ignore
        executor.defaultResult = new HostCommandExecutor.Result(0, "", "", false, true);

        IsolationResult result = isolator.run(sampleSpec());
        assertTrue(result.succeeded());
        assertEquals(GvisorRuntimeIsolator.RUNTIME_NAME, result.runtime());
        assertEquals("hello", result.stdout());
        assertTrue(result.meta().get("dockerRuntime").equals("runsc"));

        List<String> runCmd = executor.commands.stream()
                .filter(c -> c.size() > 2 && "run".equals(c.get(1)))
                .findFirst()
                .orElseThrow();
        assertEquals("runsc", runCmd.get(3));
        assertEquals("--runtime", runCmd.get(2));
    }

    /**
     * Optional live smoke: only when {@code runsc --version} works on the host.
     * CI without gVisor skips this automatically.
     */
    @Test
    @EnabledIf("com.agentbench.execution.GvisorRuntimeIsolatorTest#runscBinaryPresent")
    void liveRunscVersionSmokeWhenPresent() {
        HostCommandExecutor live = new com.agentbench.execution.service.ProcessHostCommandExecutor();
        HostCommandExecutor.Result result = live.run(List.of("runsc", "--version"), Duration.ofSeconds(5));
        assertTrue(result.started());
        assertFalse(result.timedOut());
        assertEquals(0, result.exitCode());
        String combined = (result.stdout() + " " + result.stderr()).toLowerCase();
        assertTrue(combined.contains("runsc") || combined.contains("gvisor") || combined.contains("version"));
    }

    static boolean runscBinaryPresent() {
        try {
            Process p = new ProcessBuilder("runsc", "--version").redirectErrorStream(true).start();
            boolean finished = p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
            if (!finished) {
                p.destroyForcibly();
                return false;
            }
            return p.exitValue() == 0;
        } catch (Exception ex) {
            return false;
        }
    }

    private IsolationSpec sampleSpec() {
        return new IsolationSpec(
                "ab-test-visible_tests",
                ExecutionStage.VISIBLE_TESTS,
                temp,
                null,
                List.of("echo", "ok"),
                Duration.ofSeconds(30),
                256,
                0.5,
                64,
                false,
                true,
                Map.of()
        );
    }

    private static final class RecordingExecutor implements HostCommandExecutor {
        private final List<List<String>> commands = new ArrayList<>();
        private final List<Result> queue = new ArrayList<>();
        private final List<Match> matches = new ArrayList<>();
        private Result defaultResult = Result.failedToStart("no queued result");

        void enqueue(Result result) {
            queue.add(result);
        }

        void onMatch(java.util.function.Predicate<List<String>> predicate, Result result) {
            matches.add(new Match(predicate, result));
        }

        @Override
        public Result run(List<String> command, Duration timeout) {
            commands.add(List.copyOf(command));
            for (Match match : matches) {
                if (match.predicate.test(command)) {
                    return match.result;
                }
            }
            if (!queue.isEmpty()) {
                return queue.remove(0);
            }
            return defaultResult;
        }

        private record Match(java.util.function.Predicate<List<String>> predicate, Result result) {
        }
    }
}
