package com.agentbench.execution.quality;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * Parses ESLint {@code -f json} output arrays into {@link QualityToolReport}.
 */
public final class EslintReportParser {

    public static final String TOOL = "eslint";

    private EslintReportParser() {
    }

    public static QualityToolReport parse(String json) {
        return parse(json, new ObjectMapper(), "parsed_eslint_json");
    }

    public static QualityToolReport parse(String json, ObjectMapper objectMapper, String notes) {
        if (json == null || json.isBlank()) {
            return QualityToolReport.stub(TOOL, "empty_report");
        }
        try {
            JsonNode root = objectMapper.readTree(json.trim());
            if (!root.isArray()) {
                return QualityToolReport.stub(TOOL, "unexpected_shape");
            }
            List<QualityFinding> findings = new ArrayList<>();
            for (JsonNode file : root) {
                String path = text(file, "filePath", text(file, "file", ""));
                JsonNode messages = file.path("messages");
                if (!messages.isArray()) {
                    continue;
                }
                for (JsonNode msg : messages) {
                    String rule = text(msg, "ruleId", "eslint");
                    String message = text(msg, "message", "");
                    int line = msg.path("line").asInt(0);
                    String severity = mapSeverity(msg.path("severity").asInt(1));
                    String fp = TOOL + "|" + rule + "|" + path + "|" + line + "|" + message;
                    findings.add(new QualityFinding(TOOL, rule, severity, path, line, message, fp));
                }
            }
            return new QualityToolReport(TOOL, findings, false, notes == null ? "parsed_eslint_json" : notes);
        } catch (Exception ex) {
            return QualityToolReport.stub(TOOL, "parse_error");
        }
    }

    private static String mapSeverity(int severity) {
        // ESLint: 1 = warning, 2 = error
        if (severity >= 2) {
            return "ERROR";
        }
        if (severity == 1) {
            return "WARNING";
        }
        return "INFO";
    }

    private static String text(JsonNode node, String field, String fallback) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull() || !value.isTextual()) {
            return fallback;
        }
        String text = value.asText();
        return text == null || text.isBlank() ? fallback : text;
    }
}
