package com.agentbench.execution.quality;

import com.agentbench.execution.config.ExecutionProperties;
import com.agentbench.execution.domain.ExecutionStage;
import com.agentbench.execution.domain.IsolationResult;
import com.agentbench.execution.domain.IsolationSpec;
import com.agentbench.execution.service.DockerRuntimeIsolator;
import com.agentbench.execution.service.ExecutionIsolatorService;
import com.agentbench.execution.service.FirecrackerRuntimeIsolator;
import com.agentbench.execution.service.GvisorRuntimeIsolator;
import com.agentbench.execution.service.HostCommandExecutor;
import com.agentbench.execution.service.LocalRuntimeIsolator;
import com.fasterxml.jackson.databind.ObjectMapper;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LanguageQualityAnalyzerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @TempDir
    Path temp;

    @Test
    void pythonDisabledReturnsStub() {
        PythonQualityAnalyzer analyzer = PythonQualityAnalyzer.disabled();
        QualityToolReport report = analyzer.analyze(temp);
        assertTrue(report.stub());
        assertEquals("python_quality_disabled", report.notes());
    }

    @Test
    void pythonImportsReportWhenEnabled() throws Exception {
        Path ws = temp.resolve("py");
        Files.createDirectories(ws.resolve(".agentbench"));
        Files.writeString(ws.resolve("app.py"), "print('hi')\n", StandardCharsets.UTF_8);
        Files.writeString(ws.resolve(".agentbench/ruff.json"), """
                [{"code":"T201","message":"print","filename":"app.py","location":{"row":1,"column":1}}]
                """, StandardCharsets.UTF_8);

        PythonQualityProperties props = new PythonQualityProperties();
        props.setEnabled(true);
        props.setInvokeIsolator(false);
        props.setAllowBuiltinHeuristics(false);
        PythonQualityAnalyzer analyzer = new PythonQualityAnalyzer(
                props, null, new ExecutionProperties(), objectMapper);

        QualityToolReport report = analyzer.analyze(ws);
        assertFalse(report.stub());
        assertEquals(1, report.findings().size());
        assertTrue(report.notes().contains("imported"));
    }

    @Test
    void pythonFallsBackToBuiltinHeuristics() throws Exception {
        Path ws = temp.resolve("py-builtin");
        Files.createDirectories(ws);
        Files.writeString(ws.resolve("main.py"), """
                def run():
                    print("x")
                    try:
                        pass
                    except:
                        pass
                """, StandardCharsets.UTF_8);

        PythonQualityProperties props = new PythonQualityProperties();
        props.setEnabled(true);
        props.setInvokeIsolator(false);
        props.setAllowBuiltinHeuristics(true);
        PythonQualityAnalyzer analyzer = new PythonQualityAnalyzer(
                props, null, new ExecutionProperties(), objectMapper);

        QualityToolReport report = analyzer.analyze(ws);
        assertFalse(report.stub());
        assertEquals("builtin_python_heuristics", report.notes());
        assertTrue(report.findings().size() >= 2);
    }

    @Test
    void pythonInvokesIsolatorWhenEnabled() throws Exception {
        Path ws = temp.resolve("py-iso");
        Files.createDirectories(ws);
        Files.writeString(ws.resolve("x.py"), "x = 1\n", StandardCharsets.UTF_8);

        AtomicReference<IsolationSpec> seen = new AtomicReference<>();
        ExecutionIsolatorService isolator = recordingIsolator(seen, """
                [{"code":"E501","message":"line too long","filename":"x.py","location":{"row":1,"column":1}}]
                """);

        PythonQualityProperties props = new PythonQualityProperties();
        props.setEnabled(true);
        props.setInvokeIsolator(true);
        props.setAllowBuiltinHeuristics(false);
        PythonQualityAnalyzer analyzer = new PythonQualityAnalyzer(
                props, isolator, new ExecutionProperties(), objectMapper);

        UUID evalId = UUID.randomUUID();
        QualityToolReport report = analyzer.analyze(evalId, ws);
        assertFalse(report.stub());
        assertEquals("isolator_stdout", report.notes());
        assertEquals(ExecutionStage.QUALITY, seen.get().stage());
        assertTrue(seen.get().command().contains("ruff") || seen.get().command().get(0).contains("ruff")
                || String.join(" ", seen.get().command()).contains("ruff"));
    }

    @Test
    void nodeImportsReportAndBuiltin() throws Exception {
        Path ws = temp.resolve("js");
        Files.createDirectories(ws.resolve(".agentbench"));
        Files.writeString(ws.resolve("index.js"), "console.log(1)\n", StandardCharsets.UTF_8);
        Files.writeString(ws.resolve(".agentbench/eslint.json"), """
                [{"filePath":"index.js","messages":[{"ruleId":"no-console","severity":1,"line":1,"message":"console"}]}]
                """, StandardCharsets.UTF_8);

        NodeQualityProperties props = new NodeQualityProperties();
        props.setEnabled(true);
        props.setInvokeIsolator(false);
        NodeQualityAnalyzer analyzer = new NodeQualityAnalyzer(
                props, null, new ExecutionProperties(), objectMapper);
        QualityToolReport imported = analyzer.analyze(ws);
        assertFalse(imported.stub());
        assertEquals(1, imported.findings().size());

        Path ws2 = temp.resolve("js2");
        Files.createDirectories(ws2);
        Files.writeString(ws2.resolve("a.js"), "var x = 1\nconsole.log(x)\n", StandardCharsets.UTF_8);
        QualityToolReport builtin = analyzer.analyze(ws2);
        assertFalse(builtin.stub());
        assertEquals("builtin_node_heuristics", builtin.notes());
        assertTrue(builtin.findings().size() >= 2);
    }

    @Test
    void nodeDisabledWithoutSources() throws Exception {
        Path ws = temp.resolve("empty");
        Files.createDirectories(ws);
        Files.writeString(ws.resolve("README.md"), "hi\n", StandardCharsets.UTF_8);

        NodeQualityProperties props = new NodeQualityProperties();
        props.setEnabled(true);
        NodeQualityAnalyzer analyzer = new NodeQualityAnalyzer(
                props, null, new ExecutionProperties(), objectMapper);
        QualityToolReport report = analyzer.analyze(ws);
        assertTrue(report.stub());
        assertEquals("no_node_sources", report.notes());
    }

    private ExecutionIsolatorService recordingIsolator(
            AtomicReference<IsolationSpec> seen,
            String stdout) {
        ExecutionProperties props = new ExecutionProperties();
        props.setMode("local");
        props.setDockerEnabled(false);
        return new ExecutionIsolatorService(
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
                        "ab-test-quality",
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
                seen.set(spec);
                return new IsolationResult(
                        0, stdout, "", false, false, "recording", Map.of("runtime", "recording"));
            }
        };
    }
}
