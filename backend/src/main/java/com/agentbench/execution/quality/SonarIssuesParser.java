package com.agentbench.execution.quality;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

/**
 * Parses Sonar Web API {@code /api/issues/search} JSON (or a workspace-exported equivalent)
 * into {@link QualityToolReport} findings.
 */
public final class SonarIssuesParser {

    public static final String TOOL = "sonar";

    private SonarIssuesParser() {
    }

    public static QualityToolReport parse(String json, String projectKey, String notes) {
        if (json == null || json.isBlank()) {
            return QualityToolReport.stub(TOOL, "empty_sonar_report");
        }
        try {
            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(json);
            JsonNode issuesNode = root;
            if (root.isObject()) {
                if (root.has("issues")) {
                    issuesNode = root.get("issues");
                } else if (root.has("results")) {
                    // Generic sensor / alternate export shape.
                    issuesNode = root.get("results");
                }
            }
            if (issuesNode == null || !issuesNode.isArray()) {
                return QualityToolReport.stub(TOOL, "sonar_issues_array_missing");
            }
            List<QualityFinding> findings = new ArrayList<>();
            for (JsonNode issue : issuesNode) {
                if (issue == null || !issue.isObject()) {
                    continue;
                }
                String status = text(issue, "status");
                if ("RESOLVED".equalsIgnoreCase(status) || "CLOSED".equalsIgnoreCase(status)) {
                    continue;
                }
                String rule = firstNonBlank(text(issue, "rule"), text(issue, "ruleId"), "sonar");
                String severity = firstNonBlank(text(issue, "severity"), "INFO");
                String component = firstNonBlank(
                        text(issue, "component"),
                        text(issue, "path"),
                        text(issue, "filePath"),
                        "");
                String filePath = stripProjectPrefix(component, projectKey);
                int line = issue.has("line") && issue.get("line").canConvertToInt()
                        ? issue.get("line").asInt(0)
                        : issue.path("textRange").path("startLine").asInt(0);
                String message = firstNonBlank(text(issue, "message"), "");
                String fp = TOOL + "|" + rule + "|" + filePath + "|" + line + "|" + message;
                findings.add(new QualityFinding(TOOL, rule, severity, filePath, line, message, fp));
            }
            String label = notes == null || notes.isBlank() ? "parsed_sonar_issues_json" : notes;
            return new QualityToolReport(TOOL, findings, false, label);
        } catch (Exception ex) {
            return QualityToolReport.stub(TOOL, "sonar_parse_error");
        }
    }

    static String stripProjectPrefix(String component, String projectKey) {
        if (component == null) {
            return "";
        }
        String path = component.replace('\\', '/');
        if (projectKey != null && !projectKey.isBlank()) {
            String prefix = projectKey + ":";
            if (path.startsWith(prefix)) {
                path = path.substring(prefix.length());
            }
        } else {
            int colon = path.indexOf(':');
            if (colon > 0 && colon < path.length() - 1 && path.indexOf('/') > colon) {
                path = path.substring(colon + 1);
            }
        }
        return path;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return "";
        }
        return value.asText("").trim();
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    /** Maps Sonar severity strings to a stable uppercase label (passthrough when already valid). */
    static String normalizeSeverity(String severity) {
        if (severity == null || severity.isBlank()) {
            return "INFO";
        }
        return severity.trim().toUpperCase(Locale.ROOT);
    }

    /** Exposed for tests that assert issue-key iteration stability. */
    static int countIssueObjects(JsonNode issuesNode) {
        if (issuesNode == null || !issuesNode.isArray()) {
            return 0;
        }
        int count = 0;
        for (Iterator<JsonNode> it = issuesNode.elements(); it.hasNext(); ) {
            JsonNode n = it.next();
            if (n != null && n.isObject()) {
                count++;
            }
        }
        return count;
    }
}
