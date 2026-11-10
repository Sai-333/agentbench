package com.agentbench.execution.quality;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * Parses ruff {@code --output-format=json} arrays into {@link QualityToolReport}.
 */
public final class RuffReportParser {

    public static final String TOOL = "ruff";

    private RuffReportParser() {
    }

    public static QualityToolReport parse(String json) {
        return parse(json, new ObjectMapper(), "parsed_ruff_json");
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
            for (JsonNode item : root) {
                String code = text(item, "code", "ruff");
                String message = text(item, "message", "");
                String filename = text(item, "filename", text(item, "file", ""));
                int line = item.path("location").path("row").asInt(
                        item.path("location").path("line").asInt(
                                item.path("lineno").asInt(0)));
                String severity = mapSeverity(item);
                String fp = TOOL + "|" + code + "|" + filename + "|" + line + "|" + message;
                findings.add(new QualityFinding(TOOL, code, severity, filename, line, message, fp));
            }
            return new QualityToolReport(TOOL, findings, false, notes == null ? "parsed_ruff_json" : notes);
        } catch (Exception ex) {
            return QualityToolReport.stub(TOOL, "parse_error");
        }
    }

    private static String mapSeverity(JsonNode item) {
        String explicit = text(item, "severity", "");
        if (!explicit.isBlank()) {
            return explicit;
        }
        // Ruff codes: E/F often errors; W warnings; others info-ish.
        String code = text(item, "code", "");
        if (code.startsWith("E") || code.startsWith("F")) {
            return "ERROR";
        }
        if (code.startsWith("W")) {
            return "WARNING";
        }
        return "WARNING";
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
