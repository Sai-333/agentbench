package com.agentbench.execution.service;

import com.agentbench.execution.config.ExecutionProperties;
import com.agentbench.execution.domain.ExecutionStage;
import com.agentbench.execution.domain.IsolationResult;
import com.agentbench.execution.domain.IsolationSpec;
import com.agentbench.execution.domain.RuntimeIsolator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Facade selecting gVisor, Docker, or local fallback per {@code agentbench.execution.mode}.
 *
 * <p>Policy: {@code gvisor} is fail-fast (clear error if runsc/Docker unavailable).
 * {@code docker} may fall back to local when the daemon is unreachable. {@code local} forces
 * host-process isolation (CI only).
 */
@Service
public class ExecutionIsolatorService {

    private static final Logger log = LoggerFactory.getLogger(ExecutionIsolatorService.class);

    private final DockerRuntimeIsolator dockerIsolator;
    private final GvisorRuntimeIsolator gvisorIsolator;
    private final LocalRuntimeIsolator localIsolator;
    private final ExecutionProperties properties;

    public ExecutionIsolatorService(
            DockerRuntimeIsolator dockerIsolator,
            GvisorRuntimeIsolator gvisorIsolator,
            LocalRuntimeIsolator localIsolator,
            ExecutionProperties properties) {
        this.dockerIsolator = dockerIsolator;
        this.gvisorIsolator = gvisorIsolator;
        this.localIsolator = localIsolator;
        this.properties = properties;
    }

    public RuntimeIsolator activeIsolator() {
        if (properties.isGvisorMode()) {
            if (!gvisorIsolator.available()) {
                throw new IllegalStateException(GvisorRuntimeIsolator.unavailableMessage());
            }
            log.info("Using gVisor RuntimeIsolator (docker --runtime={})", properties.getGvisorRuntime());
            return gvisorIsolator;
        }
        if (properties.isLocalMode()) {
            log.info("Using local runtime isolator (mode=local). "
                    + "Not production-safe for arbitrary untrusted workloads.");
            return localIsolator;
        }
        if (dockerIsolator.available()) {
            return dockerIsolator;
        }
        log.info("Using local runtime isolator (Docker unavailable or mode=local). "
                + "Not production-safe for arbitrary untrusted workloads.");
        return localIsolator;
    }

    public IsolationResult runStage(
            UUID evaluationId,
            ExecutionStage stage,
            Path workspace,
            Path hiddenFixturesDir,
            List<String> command,
            Integer memoryMb,
            Double cpuLimit,
            Integer timeoutSec,
            boolean networkEnabled) {
        if (stage != ExecutionStage.HIDDEN_TESTS && hiddenFixturesDir != null) {
            throw new IllegalArgumentException("Hidden fixtures only allowed for HIDDEN_TESTS stage");
        }
        RuntimeIsolator isolator = activeIsolator();
        IsolationSpec spec = new IsolationSpec(
                "ab-" + evaluationId.toString().substring(0, 8) + "-" + stage.name().toLowerCase(),
                stage,
                workspace,
                stage == ExecutionStage.HIDDEN_TESTS ? hiddenFixturesDir : null,
                command,
                Duration.ofSeconds(timeoutSec != null ? timeoutSec : properties.getDefaultTimeoutSec()),
                memoryMb != null ? memoryMb : properties.getDefaultMemoryMb(),
                cpuLimit != null ? cpuLimit : properties.getDefaultCpuLimit(),
                properties.getDefaultPidsLimit(),
                networkEnabled || properties.isNetworkEnabledByDefault(),
                properties.isReadOnlyRootFs(),
                Map.of()
        );
        return isolator.run(spec);
    }

    public void cleanupEvaluation(UUID evaluationId) {
        String prefix = "ab-" + evaluationId.toString().substring(0, 8);
        dockerIsolator.cleanup(prefix + "-agent");
        dockerIsolator.cleanup(prefix + "-visible_tests");
        dockerIsolator.cleanup(prefix + "-hidden_tests");
        dockerIsolator.cleanup(prefix + "-quality");
    }

    public ExecutionProperties properties() {
        return properties;
    }
}
