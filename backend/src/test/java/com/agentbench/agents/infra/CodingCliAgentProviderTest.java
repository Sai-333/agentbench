package com.agentbench.agents.infra;

import com.agentbench.agents.domain.Agent;
import com.agentbench.agents.domain.spi.AgentInvocationRequest;
import com.agentbench.agents.domain.spi.AgentInvocationResult;
import com.agentbench.agents.domain.spi.AgentInvocationStatus;
import com.agentbench.execution.config.ExecutionProperties;
import com.agentbench.execution.domain.ExecutionStage;
import com.agentbench.execution.domain.IsolationResult;
import com.agentbench.execution.domain.IsolationSpec;
import com.agentbench.execution.domain.RuntimeIsolator;
import com.agentbench.execution.service.DockerRuntimeIsolator;
import com.agentbench.execution.service.ExecutionIsolatorService;
import com.agentbench.execution.service.GvisorRuntimeIsolator;
import com.agentbench.execution.service.LocalRuntimeIsolator;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodingCliAgentProviderTest {

    @TempDir
    Path temp;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private RecordingIsolator recordingIsolator;
    private CodingCliAgentProvider provider;

    @BeforeEach
    void setUp() {
        recordingIsolator = new RecordingIsolator();
        ExecutionProperties props = new ExecutionProperties();
        props.setMode("local");
        props.setDockerEnabled(false);
        provider = new CodingCliAgentProvider(objectMapper, new ExecutionIsolatorService(
                new DockerRuntimeIsolator(props),
                new GvisorRuntimeIsolator(props, new DockerRuntimeIsolator(props),
                        (c, t) -> com.agentbench.execution.service.HostCommandExecutor.Result.failedToStart("n/a")),
                new LocalRuntimeIsolator(),
                props
        ) {
            @Override
            public IsolationResult runStage(
                    UUID evaluationId,
                    ExecutionStage stage,
                    Path workspace,
                    Path hiddenFixturesDir,
                    List<String> command,
                    Integer memoryMb,
                    Double cpuLimit,
                    Integer timeoutSec,
                    boolean networkEnabled,
                    String imageOverride,
                    Map<String, String> env) {
                IsolationSpec spec = new IsolationSpec(
                        "ab-test-agent",
                        stage,
                        workspace,
                        null,
                        command,
                        Duration.ofSeconds(timeoutSec == null ? 30 : timeoutSec),
                        memoryMb == null ? 256 : memoryMb,
                        cpuLimit == null ? 0.5 : cpuLimit,
                        64,
                        networkEnabled,
                        true,
                        env,
                        imageOverride
                );
                return recordingIsolator.run(spec);
            }
        });
    }

    @Test
    void fixturePatchDoesNotSpawnIsolator() {
        Agent agent = agent("{\"patch\":\"diff --git a/x b/x\\n\",\"model\":\"fixture\"}");
        AgentInvocationResult result = provider.invoke(request(agent, null));
        assertEquals(AgentInvocationStatus.SUCCEEDED, result.status());
        assertTrue(result.patchContent().contains("diff --git"));
        assertEquals("fixture-patch", result.metadata().get("mode"));
        assertEquals("fixture-no-spawn", result.metadata().get("isolation"));
        assertFalse(recordingIsolator.wasInvoked());
    }

    @Test
    void isolatorPathCapturesPatchFileAndMapsMetadata() throws Exception {
        Path workspace = Files.createDirectories(temp.resolve("ws"));
        Files.writeString(workspace.resolve("README.md"), "AgentBench evaluation workspace\n");

        String patch = """
                diff --git a/README.md b/README.md
                --- a/README.md
                +++ b/README.md
                @@ -1 +1,2 @@
                 AgentBench evaluation workspace
                +Patched by CODING_CLI
                """;
        recordingIsolator.setHandler(spec -> {
            assertEquals(ExecutionStage.AGENT, spec.stage());
            assertEquals(List.of("my-agent", "--apply", "/workspace/.agentbench/prompt.txt"), spec.command());
            assertEquals("coding-agent:latest", spec.imageOverride());
            assertTrue(spec.env().containsKey("AGENTBENCH_PATCH_FILE"));
            try {
                Path patchFile = spec.workspace().resolve(".agentbench/patch.diff");
                Files.createDirectories(patchFile.getParent());
                Files.writeString(patchFile, patch, StandardCharsets.UTF_8);
            } catch (Exception ex) {
                throw new RuntimeException(ex);
            }
            return new IsolationResult(0, "", "", false, false, "mock-isolator", Map.of("mock", true));
        });

        String config = """
                {
                  "binary": "my-agent",
                  "args": ["--apply", "{{promptFile}}"],
                  "image": "coding-agent:latest",
                  "model": "cli-model",
                  "timeoutSec": 45
                }
                """;
        Agent agent = agent(config);
        AgentInvocationResult result = provider.invoke(request(agent, workspace.toString()));

        assertEquals(AgentInvocationStatus.SUCCEEDED, result.status());
        assertTrue(result.patchContent().contains("Patched by CODING_CLI"));
        assertEquals(CodingCliAgentProvider.PROVIDER_VERSION, result.providerVersion());
        assertEquals("isolator", result.metadata().get("mode"));
        assertEquals("mock-isolator", result.metadata().get("isolation"));
        assertEquals("patchFile", result.metadata().get("patchSource"));
        assertTrue(Files.isRegularFile(workspace.resolve(CodingCliAgentProvider.PROMPT_RELATIVE)));
        assertTrue(recordingIsolator.wasInvoked());
    }

    @Test
    void extractsPatchFromStdoutWhenFileMissing() {
        Path workspace = temp.resolve("stdout-ws");
        try {
            Files.createDirectories(workspace);
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
        recordingIsolator.setHandler(spec -> new IsolationResult(
                0,
                "```diff\ndiff --git a/a b/a\n--- a/a\n+++ b/a\n@@ -1 +1 @@\n-old\n+new\n```\n",
                "",
                false,
                false,
                "mock-isolator",
                Map.of()
        ));

        Agent agent = agent("{\"argv\":[\"echo\",\"patch\"]}");
        AgentInvocationResult result = provider.invoke(request(agent, workspace.toString()));
        assertEquals(AgentInvocationStatus.SUCCEEDED, result.status());
        assertTrue(result.patchContent().contains("diff --git"));
        assertEquals("stdout", result.metadata().get("patchSource"));
    }

    @Test
    void mapsTimeoutAndNonZeroExit() {
        Path workspace = temp.resolve("fail-ws");
        try {
            Files.createDirectories(workspace);
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }

        recordingIsolator.setHandler(spec -> new IsolationResult(
                -1, "", "hung", true, true, "mock-isolator", Map.of()));
        Agent agent = agent("{\"argv\":[\"hang\"]}");
        AgentInvocationResult timedOut = provider.invoke(request(agent, workspace.toString()));
        assertEquals(AgentInvocationStatus.TIMED_OUT, timedOut.status());
        assertEquals("CODING_CLI_TIMEOUT", timedOut.errorCode());

        recordingIsolator.setHandler(spec -> new IsolationResult(
                7, "", "boom", false, false, "mock-isolator", Map.of()));
        AgentInvocationResult failed = provider.invoke(request(agent, workspace.toString()));
        assertEquals(AgentInvocationStatus.FAILED, failed.status());
        assertEquals("CODING_CLI_EXIT_7", failed.errorCode());
    }

    @Test
    void requiresWorkspaceWithoutFixturePatch() {
        Agent agent = agent("{\"binary\":\"coding-agent\"}");
        AgentInvocationResult result = provider.invoke(request(agent, null));
        assertEquals(AgentInvocationStatus.FAILED, result.status());
        assertEquals("CODING_CLI_WORKSPACE_REQUIRED", result.errorCode());
        assertFalse(recordingIsolator.wasInvoked());
    }

    @Test
    void rejectsUnsafeArgv() {
        Path workspace = temp.resolve("unsafe-ws");
        try {
            Files.createDirectories(workspace);
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
        Agent agent = agent("{\"argv\":[\"sh\",\"-c\",\"mount /var/run/docker.sock\"]}");
        AgentInvocationResult result = provider.invoke(request(agent, workspace.toString()));
        assertEquals(AgentInvocationStatus.FAILED, result.status());
        assertEquals("CODING_CLI_INVALID_CONFIG", result.errorCode());
        assertFalse(recordingIsolator.wasInvoked());
    }

    @Test
    void extractPatchHelpers() {
        assertTrue(CodingCliAgentProvider.extractPatchFromStdout(
                "```diff\ndiff --git a/x b/x\n```").contains("diff --git"));
        assertTrue(CodingCliAgentProvider.extractPatchFromStdout(
                "noise\ndiff --git a/x b/x\n").startsWith("diff --git"));
    }

    @Test
    @EnabledIf("dockerAvailable")
    void dockerIsolatorSmokeWritesPatchWithoutHostCodingBinary() throws Exception {
        ExecutionProperties props = new ExecutionProperties();
        props.setMode("docker");
        props.setDockerEnabled(true);
        props.setImage("eclipse-temurin:21-jre-alpine");
        DockerRuntimeIsolator docker = new DockerRuntimeIsolator(props);
        org.junit.jupiter.api.Assumptions.assumeTrue(docker.available(), "Docker daemon not available");

        ExecutionIsolatorService service = new ExecutionIsolatorService(
                docker,
                new GvisorRuntimeIsolator(props, docker,
                        (c, t) -> com.agentbench.execution.service.HostCommandExecutor.Result.failedToStart("n/a")),
                new LocalRuntimeIsolator(),
                props
        );
        CodingCliAgentProvider live = new CodingCliAgentProvider(objectMapper, service);

        Path workspace = Files.createDirectories(temp.resolve("docker-ws"));
        Files.writeString(workspace.resolve("README.md"), "hello\n");

        // Use the stock runner image + shell — no coding-agent binary required on host or image.
        String config = """
                {
                  "argv": ["sh", "-c", "printf 'diff --git a/README.md b/README.md\\n--- a/README.md\\n+++ b/README.md\\n@@ -1 +1,2 @@\\n hello\\n+from-docker\\n' > .agentbench/patch.diff"],
                  "model": "docker-smoke"
                }
                """;
        Agent agent = agent(config);
        AgentInvocationResult result = live.invoke(request(agent, workspace.toString()));
        assertEquals(AgentInvocationStatus.SUCCEEDED, result.status(), () -> String.valueOf(result));
        assertNotNull(result.patchContent());
        assertTrue(result.patchContent().contains("from-docker"));
        assertEquals("isolator", result.metadata().get("mode"));
    }

    static boolean dockerAvailable() {
        try {
            Process p = new ProcessBuilder("docker", "version", "--format", "{{.Server.Version}}")
                    .redirectErrorStream(true)
                    .start();
            boolean finished = p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
            return finished && p.exitValue() == 0;
        } catch (Exception ex) {
            return false;
        }
    }

    private Agent agent(String configJson) {
        return new Agent(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "coding-cli-agent",
                "CODING_CLI",
                null,
                configJson,
                true,
                UUID.randomUUID(),
                Instant.now(),
                Instant.now()
        );
    }

    private AgentInvocationRequest request(Agent agent, String workspacePath) {
        return new AgentInvocationRequest(
                UUID.randomUUID(),
                agent.orgId(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                agent,
                "Fix the bug",
                "{\"visible\":true}",
                null,
                null,
                Duration.ofSeconds(30),
                1,
                workspacePath
        );
    }

    private static final class RecordingIsolator implements RuntimeIsolator {
        private final AtomicReference<IsolationSpec> last = new AtomicReference<>();
        private volatile java.util.function.Function<IsolationSpec, IsolationResult> handler =
                spec -> new IsolationResult(0, "", "", false, false, "mock-isolator", Map.of());

        void setHandler(java.util.function.Function<IsolationSpec, IsolationResult> handler) {
            this.handler = handler;
        }

        boolean wasInvoked() {
            return last.get() != null;
        }

        @Override
        public String runtimeName() {
            return "mock-isolator";
        }

        @Override
        public boolean available() {
            return true;
        }

        @Override
        public IsolationResult run(IsolationSpec spec) {
            last.set(spec);
            return handler.apply(spec);
        }

        @Override
        public void cleanup(String runNamePrefix) {
            // no-op
        }
    }
}
