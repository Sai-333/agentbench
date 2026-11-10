package com.agentbench.execution.quality;

import com.agentbench.execution.config.ExecutionProperties;
import com.agentbench.execution.domain.ExecutionStage;
import com.agentbench.execution.domain.IsolationResult;
import com.agentbench.execution.runner.CommandArgv;
import com.agentbench.execution.service.ExecutionIsolatorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/**
 * Runs an opt-in quality CLI (ruff / eslint) through {@link ExecutionIsolatorService}
 * on {@link ExecutionStage#QUALITY}, then parses stdout or a written report file.
 */
public final class IsolatedQualityToolRunner {

    private static final Logger log = LoggerFactory.getLogger(IsolatedQualityToolRunner.class);

    private IsolatedQualityToolRunner() {
    }

    public static QualityToolReport run(
            ExecutionIsolatorService isolatorService,
            ExecutionProperties executionProperties,
            UUID evaluationId,
            Path workspace,
            String tool,
            String image,
            List<String> defaultCommand,
            String commandOverride,
            int timeoutSec,
            List<String> reportPaths,
            Function<String, QualityToolReport> parser,
            Map<String, String> extraEnv) {
        if (isolatorService == null || evaluationId == null || workspace == null || !Files.isDirectory(workspace)) {
            return QualityToolReport.stub(tool, "isolator_unavailable");
        }
        try {
            List<String> command = CommandArgv.fromEntryOrDefault(commandOverride, defaultCommand);
            Map<String, String> env = new LinkedHashMap<>();
            env.put("HOME", "/tmp");
            env.put("TMPDIR", "/tmp");
            env.put("AGENTBENCH_QUALITY_TOOL", tool);
            if (extraEnv != null) {
                env.putAll(extraEnv);
            }

            IsolationResult result = isolatorService.runStage(
                    evaluationId,
                    ExecutionStage.QUALITY,
                    workspace,
                    null,
                    command,
                    executionProperties == null ? null : executionProperties.getDefaultMemoryMb(),
                    executionProperties == null ? null : executionProperties.getDefaultCpuLimit(),
                    timeoutSec,
                    false,
                    image,
                    env
            );

            // Prefer a report file written into the workspace.
            QualityToolReport fromFile = tryReadReport(workspace, tool, reportPaths, parser, "isolator_report_file");
            if (fromFile != null && !fromFile.stub()) {
                return fromFile;
            }

            String stdout = result == null || result.stdout() == null ? "" : result.stdout();
            String json = extractJsonArray(stdout);
            if (json != null) {
                QualityToolReport parsed = parser.apply(json);
                if (!parsed.stub()) {
                    return new QualityToolReport(
                            parsed.tool(),
                            parsed.findings(),
                            false,
                            "isolator_stdout"
                    );
                }
            }

            if (result != null && result.timedOut()) {
                return QualityToolReport.stub(tool, "isolator_timeout");
            }
            String stderr = result == null || result.stderr() == null ? "" : result.stderr().trim();
            String detail = !stderr.isEmpty() ? stderr : ("exit=" + (result == null ? -1 : result.exitCode()));
            log.debug("Quality tool {} isolator produced no parseable report: {}", tool, detail);
            return QualityToolReport.stub(tool, "isolator_no_report");
        } catch (IllegalArgumentException ex) {
            return QualityToolReport.stub(tool, "isolator_rejected_command");
        } catch (Exception ex) {
            return QualityToolReport.stub(tool, "isolator_error");
        }
    }

    static QualityToolReport tryReadReport(
            Path workspace,
            String tool,
            List<String> reportPaths,
            Function<String, QualityToolReport> parser,
            String notes) {
        if (workspace == null || reportPaths == null) {
            return null;
        }
        Path root = workspace.toAbsolutePath().normalize();
        for (String relative : reportPaths) {
            if (relative == null || relative.isBlank() || relative.contains("..")) {
                continue;
            }
            Path path = workspace.resolve(relative).normalize();
            if (!path.startsWith(root) || !Files.isRegularFile(path)) {
                continue;
            }
            try {
                String json = Files.readString(path, StandardCharsets.UTF_8);
                QualityToolReport report = parser.apply(json);
                if (report != null && !report.stub()) {
                    return new QualityToolReport(report.tool(), report.findings(), false, notes + ":" + relative);
                }
                return report == null ? QualityToolReport.stub(tool, "empty_report") : report;
            } catch (Exception ex) {
                return QualityToolReport.stub(tool, "report_read_error");
            }
        }
        return null;
    }

    static String extractJsonArray(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String trimmed = text.trim();
        int start = trimmed.indexOf('[');
        int end = trimmed.lastIndexOf(']');
        if (start < 0 || end <= start) {
            return null;
        }
        return trimmed.substring(start, end + 1);
    }
}
