package com.agentbench.execution.quality;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Optional Node (eslint) quality scanner for the evaluation quality stage.
 *
 * <p>Disabled by default. When enabled, prefers workspace report import, then optional
 * {@code RuntimeIsolator} invocation of eslint (node runner image), then builtin heuristics.
 * Checkstyle / SpotBugs / PMD remain the primary Java tools; this never replaces them.
 */
@ConfigurationProperties(prefix = "agentbench.quality.node")
public class NodeQualityProperties {

    /** Master switch. Default {@code false}. */
    private boolean enabled = false;

    /** Tool id recorded in findings / metrics ({@code eslint}). */
    private String tool = "eslint";

    /** Isolator timeout for live eslint runs. */
    private int timeoutSec = 60;

    /**
     * When true and no report / isolator result is available, run in-process JS/TS heuristics
     * (honest observations, not invented scores).
     */
    private boolean allowBuiltinHeuristics = true;

    /**
     * When true and an evaluation id is present, attempt eslint via {@code RuntimeIsolator}
     * using the node runner image.
     */
    private boolean invokeIsolator = true;

    /**
     * Relative workspace paths checked for an eslint JSON export before invoking the isolator.
     */
    private List<String> reportPaths = List.of(
            ".agentbench/eslint.json",
            "eslint-report.json",
            "reports/eslint.json",
            "target/eslint.json"
    );

    /**
     * Optional command override (whitespace-split / {@code sh -c} via {@code CommandArgv}).
     * Blank → default eslint JSON formatter against the image-bundled config.
     */
    private String command = "";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getTool() {
        return tool == null || tool.isBlank() ? "eslint" : tool.trim();
    }

    public void setTool(String tool) {
        this.tool = tool == null ? "eslint" : tool.trim();
    }

    public int getTimeoutSec() {
        return timeoutSec;
    }

    public void setTimeoutSec(int timeoutSec) {
        this.timeoutSec = Math.max(1, timeoutSec);
    }

    public boolean isAllowBuiltinHeuristics() {
        return allowBuiltinHeuristics;
    }

    public void setAllowBuiltinHeuristics(boolean allowBuiltinHeuristics) {
        this.allowBuiltinHeuristics = allowBuiltinHeuristics;
    }

    public boolean isInvokeIsolator() {
        return invokeIsolator;
    }

    public void setInvokeIsolator(boolean invokeIsolator) {
        this.invokeIsolator = invokeIsolator;
    }

    public List<String> getReportPaths() {
        return reportPaths;
    }

    public void setReportPaths(List<String> reportPaths) {
        this.reportPaths = reportPaths == null || reportPaths.isEmpty()
                ? List.of(
                ".agentbench/eslint.json",
                "eslint-report.json",
                "reports/eslint.json",
                "target/eslint.json")
                : List.copyOf(reportPaths);
    }

    public String getCommand() {
        return command;
    }

    public void setCommand(String command) {
        this.command = command == null ? "" : command.trim();
    }
}
