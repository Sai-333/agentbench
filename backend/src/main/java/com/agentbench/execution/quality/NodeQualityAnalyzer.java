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
 * Optional Node (eslint) quality path: report import → RuntimeIsolator → builtin heuristics.
 * Disabled by default so Java Checkstyle/SpotBugs/PMD stay the primary local tools.
 */
@Component
public class NodeQualityAnalyzer {

    public static final String TOOL = EslintReportParser.TOOL;

    private final NodeQualityProperties properties;
    private final ExecutionIsolatorService isolatorService;
    private final ExecutionProperties executionProperties;
    private final ObjectMapper objectMapper;

    public NodeQualityAnalyzer(
            NodeQualityProperties properties,
            ExecutionIsolatorService isolatorService,
            ExecutionProperties executionProperties,
            ObjectMapper objectMapper) {
        this.properties = properties;
        this.isolatorService = isolatorService;
        this.executionProperties = executionProperties;
        this.objectMapper = objectMapper;
    }

    /** Disabled analyzer for unit tests that do not wire Spring beans. */
    public static NodeQualityAnalyzer disabled() {
        return new NodeQualityAnalyzer(
                new NodeQualityProperties(),
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
            return QualityToolReport.stub(TOOL, "node_quality_disabled");
        }
        if (workspace == null || !java.nio.file.Files.isDirectory(workspace)) {
            return QualityToolReport.stub(TOOL, "workspace_missing");
        }
        if (!WorkspaceLanguageProbe.hasNodeSources(workspace)) {
            return QualityToolReport.stub(TOOL, "no_node_sources");
        }

        QualityToolReport imported = IsolatedQualityToolRunner.tryReadReport(
                workspace,
                TOOL,
                properties.getReportPaths(),
                json -> EslintReportParser.parse(json, objectMapper, "imported"),
                "imported");
        if (imported != null && !imported.stub()) {
            return imported;
        }

        if (properties.isInvokeIsolator() && evaluationId != null && isolatorService != null) {
            // Image ships eslint + default flat config at /opt/agentbench-runner/eslint.config.mjs
            List<String> defaultCmd = List.of(
                    "sh", "-c",
                    "mkdir -p .agentbench && eslint . -f json --no-error-on-unmatched-pattern "
                            + "-c /opt/agentbench-runner/eslint.config.mjs "
                            + "> .agentbench/eslint.json || true"
            );
            QualityToolReport isolated = IsolatedQualityToolRunner.run(
                    isolatorService,
                    executionProperties,
                    evaluationId,
                    workspace,
                    TOOL,
                    executionProperties.resolvedNodeRunnerImage(),
                    defaultCmd,
                    properties.getCommand(),
                    properties.getTimeoutSec(),
                    properties.getReportPaths(),
                    json -> EslintReportParser.parse(json, objectMapper, "isolator"),
                    Map.of(
                            "npm_config_cache", "/tmp/npm-cache",
                            "NPM_CONFIG_CACHE", "/tmp/npm-cache"
                    )
            );
            if (!isolated.stub()) {
                return isolated;
            }
        }

        if (properties.isAllowBuiltinHeuristics()) {
            return BuiltinNodeQualityScanner.scan(workspace);
        }
        return QualityToolReport.stub(TOOL, "node_enabled_but_no_results");
    }
}
