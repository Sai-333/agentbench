package com.agentbench.execution.quality;

import com.agentbench.execution.config.ExecutionProperties;
import com.agentbench.execution.service.ExecutionIsolatorService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Optional Python (ruff) quality path: report import → RuntimeIsolator → builtin heuristics.
 * Disabled by default so Java Checkstyle/SpotBugs/PMD stay the primary local tools.
 */
@Component
public class PythonQualityAnalyzer {

    public static final String TOOL = RuffReportParser.TOOL;

    private final PythonQualityProperties properties;
    private final ExecutionIsolatorService isolatorService;
    private final ExecutionProperties executionProperties;
    private final ObjectMapper objectMapper;

    public PythonQualityAnalyzer(
            PythonQualityProperties properties,
            ExecutionIsolatorService isolatorService,
            ExecutionProperties executionProperties,
            ObjectMapper objectMapper) {
        this.properties = properties;
        this.isolatorService = isolatorService;
        this.executionProperties = executionProperties;
        this.objectMapper = objectMapper;
    }

    /** Disabled analyzer for unit tests that do not wire Spring beans. */
    public static PythonQualityAnalyzer disabled() {
        return new PythonQualityAnalyzer(
                new PythonQualityProperties(),
                null,
                new ExecutionProperties(),
                new ObjectMapper());
    }

    public boolean isEnabled() {
        return properties != null && properties.isEnabled();
    }

    public QualityToolReport analyze(Path workspace) {
        return analyze(null, workspace);
    }

    public QualityToolReport analyze(UUID evaluationId, Path workspace) {
        if (!isEnabled()) {
            return QualityToolReport.stub(TOOL, "python_quality_disabled");
        }
        if (workspace == null || !java.nio.file.Files.isDirectory(workspace)) {
            return QualityToolReport.stub(TOOL, "workspace_missing");
        }
        if (!WorkspaceLanguageProbe.hasPythonSources(workspace)) {
            return QualityToolReport.stub(TOOL, "no_python_sources");
        }

        QualityToolReport imported = IsolatedQualityToolRunner.tryReadReport(
                workspace,
                TOOL,
                properties.getReportPaths(),
                json -> RuffReportParser.parse(json, objectMapper, "imported"),
                "imported");
        if (imported != null && !imported.stub()) {
            return imported;
        }

        if (properties.isInvokeIsolator() && evaluationId != null && isolatorService != null) {
            QualityToolReport isolated = IsolatedQualityToolRunner.run(
                    isolatorService,
                    executionProperties,
                    evaluationId,
                    workspace,
                    TOOL,
                    executionProperties.resolvedPythonRunnerImage(),
                    List.of("ruff", "check", "--output-format=json", "--exit-zero", "."),
                    properties.getCommand(),
                    properties.getTimeoutSec(),
                    properties.getReportPaths(),
                    json -> RuffReportParser.parse(json, objectMapper, "isolator"),
                    Map.of(
                            "PYTHONDONTWRITEBYTECODE", "1",
                            "PYTHONPYCACHEPREFIX", "/tmp/pycache"
                    )
            );
            if (!isolated.stub()) {
                return isolated;
            }
        }

        if (properties.isAllowBuiltinHeuristics()) {
            return BuiltinPythonQualityScanner.scan(workspace);
        }
        return QualityToolReport.stub(TOOL, "python_enabled_but_no_results");
    }
}
