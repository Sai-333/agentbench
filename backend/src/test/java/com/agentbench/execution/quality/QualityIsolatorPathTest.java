package com.agentbench.execution.quality;

import com.agentbench.execution.config.ExecutionProperties;
import com.agentbench.execution.domain.ExecutionStage;
import com.agentbench.execution.domain.IsolationResult;
import com.agentbench.execution.service.ExecutionIsolatorService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import javax.tools.JavaCompiler;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * REG-QUALITY-ISOLATOR: published-evaluation quality stage through
 * {@link JavaQualityToolchain#withIsolator} (the Spring production wiring), not
 * {@link JavaQualityToolchain#inProcess()} alone.
 *
 * <p><strong>Proven here:</strong> with a non-null evaluation id (the published-run
 * quality path), Checkstyle and PMD emit real findings in-process, the QUALITY-stage
 * isolator is invoked with the Java runner image to {@code mvn compile} a Maven tree
 * that lacks class files, and after that compile succeeds SpotBugs also emits real
 * findings. Aggregate metrics match a non-stub {@code checkstyle+spotbugs+pmd} report.
 *
 * <p><strong>Not proven:</strong> a live Docker/gVisor/Firecracker quality-runner
 * container. This CI environment has no Docker socket; the isolator is a test double
 * that materializes {@code target/classes} the same way a successful runner compile
 * would. Checkstyle/PMD/SpotBugs engines still run in the test JVM (product design),
 * not as CLIs inside the runner image.
 */
@ExtendWith(MockitoExtension.class)
class QualityIsolatorPathTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    ExecutionIsolatorService isolator;

    @TempDir
    Path temp;

    @Test
    void publishedEvaluationQualityStageRunsPrimaryToolsViaIsolatorWiring() throws Exception {
        Path baseline = mavenTree(temp.resolve("baseline"), baselineSource());
        Path patched = mavenTree(temp.resolve("patched"), patchedSource());
        ExecutionProperties properties = new ExecutionProperties();
        properties.setJavaImage("agentbench/test-java-runner:ci");

        AtomicInteger compiles = new AtomicInteger();
        when(isolator.runStage(
                any(), any(), any(), any(), any(), any(), any(), any(), anyBoolean(), any(), any()
        )).thenAnswer(invocation -> {
            Path workspace = invocation.getArgument(2);
            compileIntoTargetClasses(workspace);
            compiles.incrementAndGet();
            return new IsolationResult(0, "BUILD SUCCESS", "", false, false, "test-isolator", Map.of(
                    "runtime", "mocked",
                    "stage", ExecutionStage.QUALITY.name()
            ));
        });

        QualityAnalysisService service = new QualityAnalysisService(
                objectMapper,
                SonarQualityAnalyzer.disabled(),
                PythonQualityAnalyzer.disabled(),
                NodeQualityAnalyzer.disabled(),
                JavaQualityToolchain.withIsolator(isolator, properties));

        UUID evaluationId = UUID.randomUUID();
        QualityAnalysisService.AggregatedQuality result =
                service.analyze(evaluationId, baseline, patched);

        assertTrue(compiles.get() >= 1, "QUALITY isolator must compile at least one Maven tree");
        assertEquals("checkstyle+spotbugs+pmd", result.toolLabel());
        assertTrue(result.realResults());
        assertTrue(result.newFindingCount() >= 1);
        assertNotNull(result.normalizedScore());

        JsonNode metrics = objectMapper.readTree(result.metricsJson());
        assertFalse(metrics.path("stub").asBoolean());
        for (String tool : List.of("checkstyle", "pmd", "spotbugs")) {
            JsonNode node = toolNode(metrics, tool);
            assertFalse(node.path("stub").asBoolean(), tool);
            assertFalse(node.path("notes").asText().contains("builtin_java_heuristics"), tool);
            assertFalse(node.path("notes").asText().contains(JavaQualityToolchain.DID_NOT_RUN), tool);
            assertTrue(node.path("findingsSample").size() >= 1, tool);
            JsonNode sample = node.path("findingsSample").get(0);
            assertFalse(sample.path("ruleId").asText().isBlank(), tool);
            assertFalse(sample.path("filePath").asText().isBlank(), tool);
            assertFalse(sample.path("message").asText().isBlank(), tool);
            assertTrue(sample.has("introduced"), tool);
        }
        assertTrue(hasIntroduced(metrics, "spotbugs", true));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> command = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<UUID> evalCaptor = ArgumentCaptor.forClass(UUID.class);
        verify(isolator, atLeastOnce()).runStage(
                evalCaptor.capture(),
                eq(ExecutionStage.QUALITY),
                any(Path.class),
                isNull(),
                command.capture(),
                any(),
                any(),
                any(),
                eq(true),
                eq(properties.resolvedJavaRunnerImage()),
                any()
        );
        assertEquals(evaluationId, evalCaptor.getValue());
        assertTrue(command.getValue().contains("mvn"));
        assertTrue(command.getValue().contains("compile"));
        assertEquals("agentbench/test-java-runner:ci", properties.resolvedJavaRunnerImage());
    }

    @Test
    void toolchainWithIsolatorEmitsAllThreeToolsWhenCompileSucceeds() throws Exception {
        Path workspace = mavenTree(temp.resolve("single"), patchedSource());
        ExecutionProperties properties = new ExecutionProperties();

        when(isolator.runStage(
                any(), any(), any(), any(), any(), any(), any(), any(), anyBoolean(), any(), any()
        )).thenAnswer(invocation -> {
            compileIntoTargetClasses(invocation.getArgument(2));
            return new IsolationResult(0, "", "", false, false, "test", Map.of());
        });

        UUID evaluationId = UUID.randomUUID();
        List<QualityToolReport> reports = JavaQualityToolchain.withIsolator(isolator, properties)
                .scan(evaluationId, workspace);

        assertEquals(3, reports.size());
        for (QualityToolReport report : reports) {
            assertFalse(report.stub(), report.tool());
            assertFalse(report.notes().contains(JavaQualityToolchain.DID_NOT_RUN), report.tool());
            assertFalse(report.findings().isEmpty(), report.tool());
            for (QualityFinding finding : report.findings()) {
                assertEquals(report.tool(), finding.tool());
                assertFalse(finding.ruleId().isBlank());
                assertTrue(finding.filePath().endsWith("Sample.java"), finding.filePath());
                assertFalse(finding.message().isBlank());
            }
        }

        verify(isolator).runStage(
                eq(evaluationId),
                eq(ExecutionStage.QUALITY),
                eq(workspace),
                isNull(),
                any(),
                any(),
                any(),
                any(),
                eq(true),
                eq(properties.resolvedJavaRunnerImage()),
                any()
        );
    }

    private static Path mavenTree(Path root, String source) throws Exception {
        Files.createDirectories(root.resolve("src"));
        Files.writeString(root.resolve("pom.xml"), """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.agentbench.test</groupId>
                  <artifactId>quality-isolator-fixture</artifactId>
                  <version>0.0.1</version>
                </project>
                """, StandardCharsets.UTF_8);
        Files.writeString(root.resolve("src/Sample.java"), source, StandardCharsets.UTF_8);
        return root;
    }

    /**
     * Stand-in for a successful Java-runner {@code mvn compile}: write bytecode under
     * {@code target/classes} so SpotBugs can read it. Does not start Docker.
     */
    private static void compileIntoTargetClasses(Path workspace) throws Exception {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "JDK compiler required for REG-QUALITY-ISOLATOR fixture");
        Path classes = workspace.resolve("target/classes");
        Files.createDirectories(classes);
        Path source = workspace.resolve("src/Sample.java");
        try (StandardJavaFileManager files = compiler.getStandardFileManager(
                null, Locale.ROOT, StandardCharsets.UTF_8)) {
            Boolean ok = compiler.getTask(
                    null,
                    files,
                    null,
                    List.of("-d", classes.toString(), "-g", "-encoding", "UTF-8"),
                    null,
                    files.getJavaFileObjectsFromFiles(List.of(source.toFile()))
            ).call();
            assertTrue(Boolean.TRUE.equals(ok), "fixture javac must succeed");
        }
        assertTrue(Files.isRegularFile(classes.resolve("Sample.class")));
    }

    private static JsonNode toolNode(JsonNode metrics, String tool) {
        for (JsonNode node : metrics.path("tools")) {
            if (tool.equals(node.path("tool").asText())) {
                return node;
            }
        }
        throw new AssertionError("missing tool " + tool);
    }

    private static boolean hasIntroduced(JsonNode metrics, String tool, boolean introduced) {
        for (JsonNode finding : toolNode(metrics, tool).path("findingsSample")) {
            if (finding.path("introduced").asBoolean() == introduced) {
                return true;
            }
        }
        return false;
    }

    private static String baselineSource() {
        return """
                public class Sample {
                    public String same(String value) {
                        if (value.equals("hello-this-line-is-intentionally-longer-than-one-hundred-characters-for-the-length-check")) {
                            System.out.println("matched");
                        }
                        try {
                        } catch (RuntimeException ex) {
                        }
                        return value;
                    }
                }
                """;
    }

    private static String patchedSource() {
        return """
                public class Sample {
                    public String same(String value) {
                        if (value == "hello-this-line-is-intentionally-longer-than-one-hundred-characters-for-the-length-check") {
                            System.out.println("matched");
                        }
                        try {
                        } catch (RuntimeException ex) {
                        }
                        return value;
                    }
                }
                """;
    }
}
