package com.agentbench.execution.quality;

import com.agentbench.execution.config.ExecutionProperties;
import com.agentbench.execution.domain.ExecutionStage;
import com.agentbench.execution.domain.IsolationResult;
import com.agentbench.execution.service.ExecutionIsolatorService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class JavaQualityToolchainTest {

    @Mock
    ExecutionIsolatorService isolator;

    @TempDir
    Path temp;

    @Test
    void mavenTreeWithoutClassesCompilesBeforeSpotBugs() throws Exception {
        Files.writeString(temp.resolve("pom.xml"), "<project/>\n", StandardCharsets.UTF_8);
        Files.createDirectories(temp.resolve("src"));
        Files.writeString(temp.resolve("src/Sample.java"), """
                public class Sample {
                    public String same(String value) {
                        return value == "x" ? value : value;
                    }
                }
                """, StandardCharsets.UTF_8);
        ExecutionProperties properties = new ExecutionProperties();
        when(isolator.runStage(
                any(), any(), any(), any(), any(), any(), any(), any(), anyBoolean(), any(), any()
        )).thenReturn(new IsolationResult(1, "", "", false, false, "test", Map.of()));

        List<QualityToolReport> reports = JavaQualityToolchain.withIsolator(isolator, properties)
                .scan(UUID.randomUUID(), temp);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> command = ArgumentCaptor.forClass(List.class);
        verify(isolator).runStage(
                any(),
                eq(ExecutionStage.QUALITY),
                eq(temp),
                isNull(),
                command.capture(),
                any(),
                any(),
                any(),
                eq(true),
                eq(properties.resolvedJavaRunnerImage()),
                any()
        );
        assertTrue(command.getValue().contains("mvn"));
        assertTrue(command.getValue().contains("compile"));
        QualityToolReport spotbugs = reports.stream()
                .filter(report -> "spotbugs".equals(report.tool()))
                .findFirst()
                .orElseThrow();
        assertTrue(spotbugs.stub());
        assertEquals(JavaQualityToolchain.DID_NOT_RUN, spotbugs.notes());
        assertTrue(reports.stream().anyMatch(report -> "checkstyle".equals(report.tool()) && !report.stub()));
        assertTrue(reports.stream().anyMatch(report -> "pmd".equals(report.tool()) && !report.stub()));
    }
}
