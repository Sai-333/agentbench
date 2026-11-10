package com.agentbench.execution;

import com.agentbench.execution.config.ExecutionProperties;
import com.agentbench.execution.domain.ExecutionStage;
import com.agentbench.execution.domain.IsolationResult;
import com.agentbench.execution.domain.IsolationSpec;
import com.agentbench.execution.service.FirecrackerRuntimeIsolator;
import com.agentbench.execution.service.HostCommandExecutor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FirecrackerRuntimeIsolatorTest {

    @TempDir
    Path temp;

    private ExecutionProperties props;
    private RecordingExecutor executor;
    private FirecrackerRuntimeIsolator isolator;

    @BeforeEach
    void setUp() {
        props = new ExecutionProperties();
        props.setMode("firecracker");
        props.setImage("eclipse-temurin:21-jre-alpine");
        props.setFirecrackerWrapper("agentbench-fc-run");
        props.setFirecrackerBinary("firecracker");
        props.setJailerBinary("jailer");
        props.setKvmDevice("/dev/kvm");
        props.setFirecrackerUseJailer(false);
        executor = new RecordingExecutor();
        isolator = new FirecrackerRuntimeIsolator(props, executor);
    }

    @Test
    void buildCommandUsesDocumentedWrapperContract() {
        IsolationSpec spec = sampleSpec();
        List<String> cmd = isolator.buildFirecrackerCommand(spec);
        String joined = String.join(" ", cmd);
        assertTrue(joined.startsWith("agentbench-fc-run run --name"));
        assertTrue(joined.contains("--workspace " + temp.toAbsolutePath()));
        assertTrue(joined.contains("--memory-mb 256"));
        assertTrue(joined.contains("--cpus 0.50"));
        assertTrue(joined.contains("--network none"));
        assertTrue(joined.contains("--firecracker firecracker"));
        assertTrue(joined.contains("--kvm-device /dev/kvm"));
        assertTrue(joined.contains("--read-only-rootfs"));
        assertTrue(joined.contains("--image eclipse-temurin:21-jre-alpine"));
        assertTrue(joined.contains("-- echo ok"));
        assertFalse(joined.contains("docker.sock"));
        assertFalse(joined.contains("--privileged"));
        assertFalse(joined.contains("--use-jailer"));
    }

    @Test
    void buildCommandIncludesJailerWhenEnabled() {
        props.setFirecrackerUseJailer(true);
        List<String> cmd = isolator.buildFirecrackerCommand(sampleSpec());
        assertTrue(cmd.contains("--use-jailer"));
        assertTrue(cmd.contains("--jailer"));
        assertTrue(cmd.contains("jailer"));
    }

    @Test
    void availableWhenFirecrackerKvmAndWrapperProbesSucceed() {
        executor.enqueue(new HostCommandExecutor.Result(0, "Firecracker v1.9.0", "", false, true));
        executor.enqueue(new HostCommandExecutor.Result(0, "", "", false, true)); // test -c /dev/kvm
        executor.enqueue(new HostCommandExecutor.Result(0, "Usage: agentbench-fc-run", "", false, true));
        assertTrue(isolator.available());
        assertEquals(List.of("firecracker", "--version"), executor.commands.get(0));
        assertEquals(List.of("test", "-c", "/dev/kvm"), executor.commands.get(1));
        assertEquals(List.of("agentbench-fc-run", "--help"), executor.commands.get(2));
    }

    @Test
    void unavailableWhenKvmMissing() {
        executor.enqueue(new HostCommandExecutor.Result(0, "Firecracker v1.9.0", "", false, true));
        executor.enqueue(new HostCommandExecutor.Result(1, "", "", false, true)); // test -c fails
        assertFalse(isolator.available());
    }

    @Test
    void unavailableWhenFirecrackerBinaryMissing() {
        executor.enqueue(HostCommandExecutor.Result.failedToStart("firecracker: not found"));
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
        executor.enqueue(HostCommandExecutor.Result.failedToStart("firecracker: not found"));
        isolator.resetAvailabilityCache();
        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> isolator.run(sampleSpec()));
        assertTrue(ex.getMessage().contains("Firecracker mode requested"));
        assertTrue(ex.getMessage().contains("AGENTBENCH_EXECUTION_MODE=docker"));
        assertTrue(ex.getMessage().contains("Do not claim a live microVM"));
    }

    @Test
    void runUsesWrapperWhenAvailable() {
        executor.enqueue(new HostCommandExecutor.Result(0, "Firecracker v1.9.0", "", false, true));
        executor.enqueue(new HostCommandExecutor.Result(0, "", "", false, true));
        executor.enqueue(new HostCommandExecutor.Result(0, "Usage: agentbench-fc-run", "", false, true));
        executor.onMatch(cmd -> !cmd.isEmpty() && "agentbench-fc-run".equals(cmd.get(0)) && cmd.size() > 1 && "run".equals(cmd.get(1)),
                new HostCommandExecutor.Result(0, "hello-from-microvm", "", false, true));
        executor.defaultResult = new HostCommandExecutor.Result(0, "", "", false, true);

        IsolationResult result = isolator.run(sampleSpec());
        assertTrue(result.succeeded());
        assertEquals(FirecrackerRuntimeIsolator.RUNTIME_NAME, result.runtime());
        assertEquals("hello-from-microvm", result.stdout());
        assertEquals("agentbench-fc-run", result.meta().get("wrapper"));
        assertEquals(false, result.meta().get("useJailer"));

        List<String> runCmd = executor.commands.stream()
                .filter(c -> c.size() > 1 && "run".equals(c.get(1)))
                .findFirst()
                .orElseThrow();
        assertEquals("agentbench-fc-run", runCmd.get(0));
        assertTrue(runCmd.contains("--kvm-device"));
    }

    @Test
    void jailerModeRequiresJailerProbe() {
        props.setFirecrackerUseJailer(true);
        isolator.resetAvailabilityCache();
        executor.enqueue(new HostCommandExecutor.Result(0, "Firecracker v1.9.0", "", false, true));
        executor.enqueue(new HostCommandExecutor.Result(0, "", "", false, true));
        executor.enqueue(new HostCommandExecutor.Result(0, "Usage: agentbench-fc-run", "", false, true));
        executor.enqueue(HostCommandExecutor.Result.failedToStart("jailer: not found"));
        executor.enqueue(HostCommandExecutor.Result.failedToStart("jailer: not found"));
        assertFalse(isolator.available());
    }

    /**
     * Optional live smoke: only when {@code firecracker --version} works and {@code /dev/kvm}
     * exists. CI without KVM/Firecracker skips this automatically — never claims a live microVM.
     */
    @Test
    @EnabledIf("com.agentbench.execution.FirecrackerRuntimeIsolatorTest#firecrackerAndKvmPresent")
    void liveFirecrackerVersionSmokeWhenPresent() {
        HostCommandExecutor live = new com.agentbench.execution.service.ProcessHostCommandExecutor();
        HostCommandExecutor.Result result = live.run(List.of("firecracker", "--version"), Duration.ofSeconds(5));
        assertTrue(result.started());
        assertFalse(result.timedOut());
        assertEquals(0, result.exitCode());
        String combined = (result.stdout() + " " + result.stderr()).toLowerCase();
        assertTrue(combined.contains("firecracker") || combined.contains("version"));
        assertTrue(Files.exists(Path.of("/dev/kvm")));
    }

    static boolean firecrackerAndKvmPresent() {
        try {
            if (!Files.exists(Path.of("/dev/kvm"))) {
                return false;
            }
            Process p = new ProcessBuilder("firecracker", "--version").redirectErrorStream(true).start();
            boolean finished = p.waitFor(5, TimeUnit.SECONDS);
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
