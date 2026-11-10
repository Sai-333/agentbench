package com.agentbench.execution.runner;

import com.agentbench.evaluation.domain.TestCaseStatus;
import com.agentbench.execution.config.ExecutionProperties;
import com.agentbench.execution.domain.ExecutionStage;
import com.agentbench.execution.domain.IsolationResult;
import com.agentbench.execution.domain.IsolationSpec;
import com.agentbench.execution.domain.RuntimeIsolator;
import com.agentbench.execution.service.DockerRuntimeIsolator;
import com.agentbench.execution.service.ExecutionIsolatorService;
import com.agentbench.execution.service.FirecrackerRuntimeIsolator;
import com.agentbench.execution.service.FixtureSuiteRunner;
import com.agentbench.execution.service.GvisorRuntimeIsolator;
import com.agentbench.execution.service.HostCommandExecutor;
import com.agentbench.execution.service.LocalRuntimeIsolator;
import com.agentbench.execution.service.SuiteExecutionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SuiteToolchainRunnerTest {

    private static final String JUNIT_XML = """
            <?xml version="1.0" encoding="UTF-8"?>
            <testsuite name="Demo" tests="2" failures="1" errors="0" skipped="0">
              <testcase classname="tests.test_bug" name="test_target_fix" time="0.01"/>
              <testcase classname="tests.test_bug" name="test_control" time="0.02">
                <failure message="boom">fail</failure>
              </testcase>
            </testsuite>
            """;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private ExecutionProperties props;
    private RecordingIsolator recording;
    private ExecutionIsolatorService isolatorService;

    @BeforeEach
    void setUp() {
        props = new ExecutionProperties();
        props.setMode("local");
        props.setDockerEnabled(false);
        recording = new RecordingIsolator();
        isolatorService = new ExecutionIsolatorService(
                new DockerRuntimeIsolator(props),
                new GvisorRuntimeIsolator(props, new DockerRuntimeIsolator(props),
                        (c, t) -> HostCommandExecutor.Result.failedToStart("n/a")),
                new FirecrackerRuntimeIsolator(props,
                        (c, t) -> HostCommandExecutor.Result.failedToStart("n/a")),
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
                        "ab-test-suite",
                        stage,
                        workspace,
                        hiddenFixturesDir,
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
                return recording.run(spec);
            }
        };
    }

    @Test
    void pytestRunnerSupportsFrameworkAndUsesPythonImage(@TempDir Path workspace) throws Exception {
        PytestToolchainRunner runner = new PytestToolchainRunner(isolatorService, props, objectMapper);
        assertTrue(runner.supports("pytest"));
        assertTrue(runner.supports("python"));
        assertFalse(runner.supports("fixture-v1"));

        recording.setHandler(spec -> {
            Path reports = spec.workspace().resolve("reports");
            Files.createDirectories(reports);
            Files.writeString(reports.resolve("junit.xml"), JUNIT_XML, StandardCharsets.UTF_8);
            return new IsolationResult(1, "", "", false, false, recording.runtimeName(), Map.of());
        });

        ObjectNode suite = objectMapper.createObjectNode();
        suite.put("framework", "pytest");
        suite.put("entryCommand", "");

        var outcomes = runner.run(request(workspace, suite, ExecutionStage.VISIBLE_TESTS));
        assertEquals(2, outcomes.size());
        assertEquals(TestCaseStatus.PASS, outcomes.get(0).status());
        assertEquals(TestCaseStatus.FAIL, outcomes.get(1).status());
        assertEquals(props.resolvedPythonRunnerImage(), recording.lastSpec().imageOverride());
        assertTrue(recording.lastSpec().command().stream().anyMatch(s -> s.contains("pytest")));
    }

    @Test
    void jestRunnerEmitsDefaultCommandAndNodeImage(@TempDir Path workspace) throws Exception {
        JestToolchainRunner runner = new JestToolchainRunner(isolatorService, props, objectMapper);
        assertTrue(runner.supports("jest"));
        assertTrue(runner.supports("node"));

        recording.setHandler(spec -> {
            Path reports = spec.workspace().resolve("reports");
            Files.createDirectories(reports);
            Files.writeString(reports.resolve("junit.xml"), JUNIT_XML, StandardCharsets.UTF_8);
            return new IsolationResult(0, "", "", false, false, recording.runtimeName(), Map.of());
        });

        ObjectNode suite = objectMapper.createObjectNode();
        suite.put("framework", "jest");

        var outcomes = runner.run(request(workspace, suite, ExecutionStage.VISIBLE_TESTS));
        assertEquals(2, outcomes.size());
        assertEquals(props.resolvedNodeRunnerImage(), recording.lastSpec().imageOverride());
        assertEquals("reports", recording.lastSpec().env().get("JEST_JUNIT_OUTPUT_DIR"));
        assertTrue(recording.lastSpec().command().stream().anyMatch(s -> s.contains("jest")));
    }

    @Test
    void mavenRunnerUsesJavaImageAndRespectsEntryCommand(@TempDir Path workspace) throws Exception {
        MavenJUnitToolchainRunner runner = new MavenJUnitToolchainRunner(isolatorService, props, objectMapper);
        assertTrue(runner.supports("junit"));
        assertTrue(runner.supports("maven"));

        recording.setHandler(spec -> {
            Path reports = spec.workspace().resolve("target/surefire-reports");
            Files.createDirectories(reports);
            Files.writeString(reports.resolve("TEST-Bug.xml"), JUNIT_XML, StandardCharsets.UTF_8);
            return new IsolationResult(0, "", "", false, false, recording.runtimeName(), Map.of());
        });

        ObjectNode suite = objectMapper.createObjectNode();
        suite.put("framework", "junit");
        suite.put("entryCommand", "mvn -B test");

        var outcomes = runner.run(request(workspace, suite, ExecutionStage.VISIBLE_TESTS));
        assertEquals(2, outcomes.size());
        assertEquals(props.resolvedJavaRunnerImage(), recording.lastSpec().imageOverride());
        assertEquals(List.of("mvn", "-B", "test"), recording.lastSpec().command());
    }

    @Test
    void mavenDefersToFixtureWhenBlankEntryCommandAndCasesPresent(@TempDir Path workspace) {
        MavenJUnitToolchainRunner runner = new MavenJUnitToolchainRunner(isolatorService, props, objectMapper);
        ObjectNode suite = objectMapper.createObjectNode();
        suite.put("framework", "junit");
        suite.put("entryCommand", "");
        suite.put("fixtureContent", """
                {"cases":[{"key":"target.a","baselineExpect":"FAIL","postExpect":"PASS"}]}
                """);

        var outcomes = runner.run(request(workspace, suite, ExecutionStage.VISIBLE_TESTS));
        assertTrue(outcomes.isEmpty());
        assertNull(recording.lastSpec());
    }

    @Test
    void suiteExecutionServiceRoutesPytestThroughToolchain(@TempDir Path workspace) throws Exception {
        recording.setHandler(spec -> {
            Path reports = spec.workspace().resolve("reports");
            Files.createDirectories(reports);
            Files.writeString(reports.resolve("junit.xml"), JUNIT_XML, StandardCharsets.UTF_8);
            return new IsolationResult(0, "", "", false, false, recording.runtimeName(), Map.of());
        });

        SuiteExecutionService service = new SuiteExecutionService(
                new FixtureSuiteRunner(objectMapper),
                objectMapper,
                List.of(
                        new MavenJUnitToolchainRunner(isolatorService, props, objectMapper),
                        new PytestToolchainRunner(isolatorService, props, objectMapper),
                        new JestToolchainRunner(isolatorService, props, objectMapper)
                )
        );

        ObjectNode suite = objectMapper.createObjectNode();
        suite.put("framework", "pytest");

        var outcomes = service.run(request(workspace, suite, ExecutionStage.VISIBLE_TESTS));
        assertEquals(2, outcomes.size());
        assertEquals("tests.test_bug#test_target_fix", outcomes.get(0).caseKey());
    }

    @Test
    void fixtureFrameworkStillUsesFileChecks(@TempDir Path workspace) throws Exception {
        Files.writeString(workspace.resolve("src.txt"), "broken", StandardCharsets.UTF_8);
        SuiteExecutionService service = new SuiteExecutionService(
                new FixtureSuiteRunner(objectMapper),
                objectMapper,
                List.of(
                        new MavenJUnitToolchainRunner(isolatorService, props, objectMapper),
                        new PytestToolchainRunner(isolatorService, props, objectMapper),
                        new JestToolchainRunner(isolatorService, props, objectMapper)
                )
        );

        ObjectNode suite = objectMapper.createObjectNode();
        suite.put("framework", "fixture-v1");
        suite.put("fixtureContent", """
                {
                  "cases": [
                    {
                      "key": "target.fix",
                      "role": "target",
                      "baselineChecks": [
                        {"type":"file_contains","path":"src.txt","needle":"broken","expectPresent":true}
                      ],
                      "postChecks": [
                        {"type":"file_contains","path":"src.txt","needle":"fixed","expectPresent":true}
                      ]
                    }
                  ]
                }
                """);

        var baseline = service.run(SuiteExecutionRequest.offline(
                workspace, suite, FixtureSuiteRunner.Phase.BASELINE));
        assertEquals(1, baseline.size());
        assertEquals(TestCaseStatus.PASS, baseline.get(0).status());
        assertNull(recording.lastSpec());
    }

    @Test
    void hiddenPytestOverlaysWorkspaceFiles(@TempDir Path workspace) throws Exception {
        PytestToolchainRunner runner = new PytestToolchainRunner(isolatorService, props, objectMapper);
        recording.setHandler(spec -> {
            assertTrue(Files.exists(spec.workspace().resolve("tests/test_hidden.py")));
            Path reports = spec.workspace().resolve("reports");
            Files.createDirectories(reports);
            Files.writeString(reports.resolve("junit.xml"), JUNIT_XML, StandardCharsets.UTF_8);
            return new IsolationResult(0, "", "", false, false, recording.runtimeName(), Map.of());
        });

        ObjectNode suite = objectMapper.createObjectNode();
        suite.put("framework", "pytest");
        suite.put("fixtureContent", """
                {
                  "workspace": {
                    "tests/test_hidden.py": "def test_hidden():\\n    assert True\\n"
                  }
                }
                """);

        var outcomes = runner.run(request(workspace, suite, ExecutionStage.HIDDEN_TESTS));
        assertEquals(2, outcomes.size());
        assertEquals(ExecutionStage.HIDDEN_TESTS, recording.lastSpec().stage());
    }

    @Test
    void commandArgvRejectsPrivilegedFragments() {
        try {
            CommandArgv.parse("echo --privileged");
            throw new AssertionError("expected rejection");
        } catch (IllegalArgumentException ex) {
            assertTrue(ex.getMessage().toLowerCase().contains("unsafe"));
        }
        assertEquals(List.of("sh", "-c", "pytest && echo ok"), CommandArgv.parse("pytest && echo ok"));
    }

    private SuiteExecutionRequest request(Path workspace, ObjectNode suite, ExecutionStage stage) {
        return new SuiteExecutionRequest(
                UUID.randomUUID(),
                workspace,
                suite,
                stage == ExecutionStage.HIDDEN_TESTS
                        ? FixtureSuiteRunner.Phase.POST
                        : FixtureSuiteRunner.Phase.BASELINE,
                stage,
                stage == ExecutionStage.HIDDEN_TESTS ? workspace.resolve("hidden-fixtures") : null,
                256,
                0.5,
                30,
                false
        );
    }

    private static final class RecordingIsolator implements RuntimeIsolator {
        private final AtomicReference<IsolationSpec> last = new AtomicReference<>();
        private volatile Function<IsolationSpec, IsolationResult> handler =
                spec -> new IsolationResult(0, "", "", false, false, "recording-v1", Map.of());

        void setHandler(ThrowingHandler handler) {
            this.handler = spec -> {
                try {
                    return handler.handle(spec);
                } catch (RuntimeException ex) {
                    throw ex;
                } catch (Exception ex) {
                    throw new IllegalStateException(ex);
                }
            };
        }

        IsolationSpec lastSpec() {
            return last.get();
        }

        @Override
        public String runtimeName() {
            return "recording-v1";
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
        }

        @FunctionalInterface
        interface ThrowingHandler {
            IsolationResult handle(IsolationSpec spec) throws Exception;
        }
    }
}
