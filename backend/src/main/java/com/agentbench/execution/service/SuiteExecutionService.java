package com.agentbench.execution.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Selects fixture-v1 checks or JUnit XML ingestion based on suite framework / content.
 * Docker-hosted Maven runs (M7) can drop Surefire XML under reportsDir; M6 ingests the XML.
 */
@Service
public class SuiteExecutionService {

    private final FixtureSuiteRunner fixtureSuiteRunner;
    private final ObjectMapper objectMapper;

    public SuiteExecutionService(FixtureSuiteRunner fixtureSuiteRunner, ObjectMapper objectMapper) {
        this.fixtureSuiteRunner = fixtureSuiteRunner;
        this.objectMapper = objectMapper;
    }

    public List<FixtureSuiteRunner.CaseOutcome> run(
            Path workspace,
            JsonNode suiteNode,
            FixtureSuiteRunner.Phase phase) {
        String framework = suiteNode.path("framework").asText("").toLowerCase();
        JsonNode fixture = resolveFixture(suiteNode);

        if (fixture.hasNonNull("junitXml")) {
            return JUnitXmlParser.parseXml(fixture.get("junitXml").asText(""));
        }
        String fixtureContent = suiteNode.path("fixtureContent").asText("");
        if (JUnitXmlParser.looksLikeJUnitXml(fixtureContent)) {
            return JUnitXmlParser.parseXml(fixtureContent);
        }
        if (isJUnitFramework(framework)) {
            Path reportsDir = resolveReportsDir(workspace, suiteNode, fixture);
            if (reportsDir != null && Files.isDirectory(reportsDir)) {
                List<FixtureSuiteRunner.CaseOutcome> fromDir = JUnitXmlParser.parseDirectory(reportsDir);
                if (!fromDir.isEmpty()) {
                    return fromDir;
                }
            }
            String phaseXmlField = phase == FixtureSuiteRunner.Phase.BASELINE ? "junitBaseline" : "junitPost";
            if (fixture.hasNonNull(phaseXmlField)) {
                return JUnitXmlParser.parseXml(fixture.get(phaseXmlField).asText(""));
            }
        }

        ObjectNode runnable = objectMapper.createObjectNode();
        if (fixture.has("cases")) {
            runnable.set("cases", fixture.get("cases"));
        }
        if (suiteNode.has("expectedOutcomes")) {
            runnable.set("expectedOutcomes", suiteNode.get("expectedOutcomes"));
        } else if (fixture.has("expectedOutcomes")) {
            runnable.set("expectedOutcomes", fixture.get("expectedOutcomes"));
        }
        if (!runnable.has("cases") && !runnable.has("expectedOutcomes")) {
            return fixtureSuiteRunner.run(workspace, suiteNode, phase);
        }
        return fixtureSuiteRunner.run(workspace, runnable, phase);
    }

    public JsonNode parseFixtureContent(String content) {
        return fixtureSuiteRunner.parseFixtureContent(content);
    }

    public List<JsonNode> listCaseNodes(JsonNode suiteNode) {
        JsonNode fixture = resolveFixture(suiteNode);
        JsonNode cases = fixture.path("cases");
        List<JsonNode> list = new ArrayList<>();
        if (cases.isArray()) {
            cases.forEach(list::add);
        }
        return list;
    }

    private JsonNode resolveFixture(JsonNode suiteNode) {
        if (suiteNode == null || suiteNode.isMissingNode()) {
            return objectMapper.createObjectNode();
        }
        if (suiteNode.has("cases") || suiteNode.has("junitXml") || suiteNode.has("junitBaseline")) {
            return suiteNode;
        }
        String content = suiteNode.path("fixtureContent").asText("");
        if (!content.isBlank() && !JUnitXmlParser.looksLikeJUnitXml(content)) {
            return fixtureSuiteRunner.parseFixtureContent(content);
        }
        return objectMapper.createObjectNode();
    }

    private static boolean isJUnitFramework(String framework) {
        return framework.contains("junit") || framework.contains("surefire") || framework.contains("maven");
    }

    private static Path resolveReportsDir(Path workspace, JsonNode suiteNode, JsonNode fixture) {
        String relative = firstText(
                suiteNode.path("reportsDir").asText(null),
                fixture.path("reportsDir").asText(null),
                "target/surefire-reports");
        if (relative == null || relative.isBlank() || relative.contains("..") || relative.startsWith("/")) {
            return null;
        }
        Path resolved = workspace.resolve(relative).normalize();
        if (!resolved.startsWith(workspace.toAbsolutePath().normalize())) {
            return null;
        }
        return resolved;
    }

    private static String firstText(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }
}
