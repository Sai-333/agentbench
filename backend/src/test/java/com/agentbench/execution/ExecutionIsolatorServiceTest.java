package com.agentbench.execution;

import com.agentbench.execution.config.ExecutionProperties;
import com.agentbench.execution.domain.RuntimeIsolator;
import com.agentbench.execution.service.DockerRuntimeIsolator;
import com.agentbench.execution.service.ExecutionIsolatorService;
import com.agentbench.execution.service.FirecrackerRuntimeIsolator;
import com.agentbench.execution.service.GvisorRuntimeIsolator;
import com.agentbench.execution.service.HostCommandExecutor;
import com.agentbench.execution.service.LocalRuntimeIsolator;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExecutionIsolatorServiceTest {

    @Test
    void gvisorModeFailsFastWhenUnavailable() {
        ExecutionProperties props = new ExecutionProperties();
        props.setMode("gvisor");
        props.setDockerEnabled(true);
        DockerRuntimeIsolator docker = new DockerRuntimeIsolator(props);
        HostCommandExecutor failing = (command, timeout) -> HostCommandExecutor.Result.failedToStart("missing");
        GvisorRuntimeIsolator gvisor = new GvisorRuntimeIsolator(props, docker, failing);
        FirecrackerRuntimeIsolator firecracker = new FirecrackerRuntimeIsolator(props, failing);
        ExecutionIsolatorService service = new ExecutionIsolatorService(
                docker, gvisor, firecracker, new LocalRuntimeIsolator(), props);

        IllegalStateException ex = assertThrows(IllegalStateException.class, service::activeIsolator);
        assertTrue(ex.getMessage().contains("gVisor mode requested"));
    }

    @Test
    void gvisorModeSelectsGvisorWhenProbesPass() {
        ExecutionProperties props = new ExecutionProperties();
        props.setMode("gvisor");
        props.setDockerEnabled(true);
        DockerRuntimeIsolator docker = new DockerRuntimeIsolator(props);
        QueueExecutor executor = new QueueExecutor();
        executor.enqueue(new HostCommandExecutor.Result(0, "24.0", "", false, true));
        executor.enqueue(new HostCommandExecutor.Result(0, "runsc version", "", false, true));
        GvisorRuntimeIsolator gvisor = new GvisorRuntimeIsolator(props, docker, executor);
        FirecrackerRuntimeIsolator firecracker = new FirecrackerRuntimeIsolator(
                props, (c, t) -> HostCommandExecutor.Result.failedToStart("n/a"));
        ExecutionIsolatorService service = new ExecutionIsolatorService(
                docker, gvisor, firecracker, new LocalRuntimeIsolator(), props);

        RuntimeIsolator active = service.activeIsolator();
        assertEquals(GvisorRuntimeIsolator.RUNTIME_NAME, active.runtimeName());
        assertInstanceOf(GvisorRuntimeIsolator.class, active);
    }

    @Test
    void firecrackerModeFailsFastWhenUnavailable() {
        ExecutionProperties props = new ExecutionProperties();
        props.setMode("firecracker");
        DockerRuntimeIsolator docker = new DockerRuntimeIsolator(props);
        HostCommandExecutor failing = (command, timeout) -> HostCommandExecutor.Result.failedToStart("missing");
        GvisorRuntimeIsolator gvisor = new GvisorRuntimeIsolator(props, docker, failing);
        FirecrackerRuntimeIsolator firecracker = new FirecrackerRuntimeIsolator(props, failing);
        ExecutionIsolatorService service = new ExecutionIsolatorService(
                docker, gvisor, firecracker, new LocalRuntimeIsolator(), props);

        IllegalStateException ex = assertThrows(IllegalStateException.class, service::activeIsolator);
        assertTrue(ex.getMessage().contains("Firecracker mode requested"));
    }

    @Test
    void firecrackerModeSelectsFirecrackerWhenProbesPass() {
        ExecutionProperties props = new ExecutionProperties();
        props.setMode("firecracker");
        DockerRuntimeIsolator docker = new DockerRuntimeIsolator(props);
        QueueExecutor executor = new QueueExecutor();
        executor.enqueue(new HostCommandExecutor.Result(0, "Firecracker v1.9.0", "", false, true));
        executor.enqueue(new HostCommandExecutor.Result(0, "", "", false, true));
        executor.enqueue(new HostCommandExecutor.Result(0, "Usage: agentbench-fc-run", "", false, true));
        GvisorRuntimeIsolator gvisor = new GvisorRuntimeIsolator(
                props, docker, (c, t) -> HostCommandExecutor.Result.failedToStart("n/a"));
        FirecrackerRuntimeIsolator firecracker = new FirecrackerRuntimeIsolator(props, executor);
        ExecutionIsolatorService service = new ExecutionIsolatorService(
                docker, gvisor, firecracker, new LocalRuntimeIsolator(), props);

        RuntimeIsolator active = service.activeIsolator();
        assertEquals(FirecrackerRuntimeIsolator.RUNTIME_NAME, active.runtimeName());
        assertInstanceOf(FirecrackerRuntimeIsolator.class, active);
    }

    @Test
    void localModeForcesLocalIsolator() {
        ExecutionProperties props = new ExecutionProperties();
        props.setMode("local");
        props.setDockerEnabled(true);
        DockerRuntimeIsolator docker = new DockerRuntimeIsolator(props);
        HostCommandExecutor failing = (c, t) -> HostCommandExecutor.Result.failedToStart("n/a");
        GvisorRuntimeIsolator gvisor = new GvisorRuntimeIsolator(props, docker, failing);
        FirecrackerRuntimeIsolator firecracker = new FirecrackerRuntimeIsolator(props, failing);
        ExecutionIsolatorService service = new ExecutionIsolatorService(
                docker, gvisor, firecracker, new LocalRuntimeIsolator(), props);

        assertEquals(LocalRuntimeIsolator.RUNTIME_NAME, service.activeIsolator().runtimeName());
    }

    private static final class QueueExecutor implements HostCommandExecutor {
        private final List<Result> queue = new ArrayList<>();

        void enqueue(Result result) {
            queue.add(result);
        }

        @Override
        public Result run(List<String> command, Duration timeout) {
            if (queue.isEmpty()) {
                return Result.failedToStart("empty");
            }
            return queue.remove(0);
        }
    }
}
