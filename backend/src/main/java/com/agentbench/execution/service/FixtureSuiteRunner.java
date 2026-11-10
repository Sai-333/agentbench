package com.agentbench.execution.service;

import com.agentbench.evaluation.domain.FailureCode;
import com.agentbench.evaluation.domain.TestCaseStatus;
import com.agentbench.platform.error.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Fixture-backed suite runner for CI: evaluates file checks against a workspace.
 * Isolator-backed Maven/pytest/Jest toolchains share the same {@link CaseOutcome} model
 * via {@link com.agentbench.execution.runner.SuiteToolchainRunner}.
 */
@Service
public class FixtureSuiteRunner {

    private final ObjectMapper objectMapper;

    public FixtureSuiteRunner(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public List<CaseOutcome> run(Path workspace, JsonNode suiteNode, Phase phase) {
        JsonNode cases = resolveCases(suiteNode);
        List<CaseOutcome> outcomes = new ArrayList<>();
        if (!cases.isArray() || cases.isEmpty()) {
            // Default synthetic case so baseline persistence is exercised.
            outcomes.add(new CaseOutcome("fixture.default", TestCaseStatus.PASS, 1, "No cases declared; recorded PASS"));
            return outcomes;
        }
        for (JsonNode caseNode : cases) {
            long start = System.nanoTime();
            String key = text(caseNode, "key", "case");
            try {
                TestCaseStatus status = evaluateCase(workspace, caseNode, phase);
                int durationMs = (int) Math.max(1, (System.nanoTime() - start) / 1_000_000L);
                outcomes.add(new CaseOutcome(key, status, durationMs, ""));
            } catch (Exception ex) {
                int durationMs = (int) Math.max(1, (System.nanoTime() - start) / 1_000_000L);
                outcomes.add(new CaseOutcome(key, TestCaseStatus.ERROR, durationMs, sanitize(ex.getMessage())));
            }
        }
        return outcomes;
    }

    public JsonNode parseFixtureContent(String fixtureContent) {
        if (fixtureContent == null || fixtureContent.isBlank()) {
            return objectMapper.createObjectNode();
        }
        String trimmed = fixtureContent.trim();
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            try {
                return objectMapper.readTree(trimmed);
            } catch (Exception ex) {
                // Non-JSON fixtures are treated as opaque; no executable cases.
                return objectMapper.createObjectNode();
            }
        }
        return objectMapper.createObjectNode();
    }

    private TestCaseStatus evaluateCase(Path workspace, JsonNode caseNode, Phase phase) throws Exception {
        JsonNode checks = phase == Phase.BASELINE
                ? firstPresent(caseNode, "baselineChecks", "checks")
                : firstPresent(caseNode, "postChecks", "passWhen", "checks");

        if (checks != null && checks.isArray() && !checks.isEmpty()) {
            boolean allPass = true;
            for (JsonNode check : checks) {
                if (!evaluateCheck(workspace, check)) {
                    allPass = false;
                    break;
                }
            }
            return allPass ? TestCaseStatus.PASS : TestCaseStatus.FAIL;
        }

        String expectField = phase == Phase.BASELINE ? "baselineExpect" : "postExpect";
        if (caseNode.hasNonNull(expectField)) {
            return TestCaseStatus.valueOf(caseNode.get(expectField).asText().trim().toUpperCase());
        }
        // Target cases default to FAIL at baseline / PASS post when unmarked.
        if (phase == Phase.BASELINE && caseNode.path("role").asText("").equalsIgnoreCase("target")) {
            return TestCaseStatus.FAIL;
        }
        return TestCaseStatus.PASS;
    }

    private boolean evaluateCheck(Path workspace, JsonNode check) throws Exception {
        String type = text(check, "type", "file_contains");
        String relative = text(check, "path", "");
        if (relative.contains("..") || relative.startsWith("/") || relative.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, FailureCode.RUNNER_ERROR.name(), "Illegal check path");
        }
        Path file = workspace.resolve(relative).normalize();
        if (!file.startsWith(workspace.toAbsolutePath().normalize())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, FailureCode.RUNNER_ERROR.name(), "Check path escaped workspace");
        }
        return switch (type) {
            case "file_exists" -> Files.exists(file) == check.path("expectPresent").asBoolean(true);
            case "file_contains" -> {
                boolean expectPresent = check.path("expectPresent").asBoolean(true);
                String needle = text(check, "needle", "");
                if (!Files.exists(file)) {
                    yield !expectPresent;
                }
                String content = Files.readString(file, StandardCharsets.UTF_8);
                yield content.contains(needle) == expectPresent;
            }
            case "file_equals" -> {
                String expected = text(check, "content", "");
                if (!Files.exists(file)) {
                    yield false;
                }
                yield Files.readString(file, StandardCharsets.UTF_8).equals(expected);
            }
            default -> throw new ApiException(HttpStatus.BAD_REQUEST, FailureCode.RUNNER_ERROR.name(),
                    "Unknown check type: " + type);
        };
    }

    private JsonNode resolveCases(JsonNode suiteNode) {
        if (suiteNode == null || suiteNode.isNull() || suiteNode.isMissingNode()) {
            return objectMapper.createArrayNode();
        }
        if (suiteNode.has("cases")) {
            return suiteNode.get("cases");
        }
        if (suiteNode.has("fixtureContent")) {
            JsonNode parsed = parseFixtureContent(suiteNode.get("fixtureContent").asText(""));
            if (parsed.has("cases")) {
                return parsed.get("cases");
            }
        }
        if (suiteNode.isArray()) {
            return suiteNode;
        }
        // expectedOutcomes map → synthetic cases
        JsonNode outcomes = suiteNode.path("expectedOutcomes");
        if (outcomes.isObject() && !outcomes.isEmpty()) {
            var array = objectMapper.createArrayNode();
            Iterator<Map.Entry<String, JsonNode>> fields = outcomes.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                var node = objectMapper.createObjectNode();
                node.put("key", entry.getKey());
                node.put("baselineExpect", entry.getValue().asText("FAIL"));
                array.add(node);
            }
            return array;
        }
        return objectMapper.createArrayNode();
    }

    private static JsonNode firstPresent(JsonNode node, String... fields) {
        for (String field : fields) {
            JsonNode child = node.get(field);
            if (child != null && !child.isNull() && !child.isMissingNode()) {
                return child;
            }
        }
        return null;
    }

    private static String text(JsonNode node, String field, String defaultValue) {
        JsonNode child = node.get(field);
        if (child == null || child.isNull()) {
            return defaultValue;
        }
        return child.asText(defaultValue);
    }

    private static String sanitize(String message) {
        if (message == null) {
            return "";
        }
        return message.replaceAll("/[A-Za-z0-9_./-]+", "[path]").substring(0, Math.min(message.length(), 240));
    }

    public enum Phase {
        BASELINE,
        POST
    }

    public record CaseOutcome(String caseKey, TestCaseStatus status, int durationMs, String message) {
    }
}
