package com.agentbench.execution.quality;

/**
 * Normalized static-analysis finding. {@code fingerprint} is stable across baseline/post
 * so newly introduced vs pre-existing can be separated.
 */
public record QualityFinding(
        String tool,
        String ruleId,
        String severity,
        String filePath,
        int line,
        String message,
        String fingerprint
) {
    public QualityFinding {
        tool = tool == null ? "unknown" : tool;
        ruleId = ruleId == null ? "unknown" : ruleId;
        severity = severity == null ? "INFO" : severity.toUpperCase();
        filePath = filePath == null ? "" : filePath.replace('\\', '/');
        message = message == null ? "" : message;
        if (fingerprint == null || fingerprint.isBlank()) {
            fingerprint = tool + "|" + ruleId + "|" + filePath + "|" + line + "|" + message.hashCode();
        }
    }
}
