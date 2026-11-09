package com.agentbench.execution.service;

import com.agentbench.evaluation.domain.TestCaseStatus;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Ingests Maven Surefire / JUnit 5 XML reports into stable case identities.
 * Case key format: {@code classname#method} (or classname when method absent).
 */
public final class JUnitXmlParser {

    private JUnitXmlParser() {
    }

    public static List<FixtureSuiteRunner.CaseOutcome> parseXml(String xml) {
        if (xml == null || xml.isBlank()) {
            return List.of();
        }
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setExpandEntityReferences(false);
            Document doc = factory.newDocumentBuilder()
                    .parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
            Map<String, FixtureSuiteRunner.CaseOutcome> byKey = new LinkedHashMap<>();
            collectTestcases(doc.getDocumentElement(), byKey);
            return List.copyOf(byKey.values());
        } catch (Exception ex) {
            throw new IllegalArgumentException("Invalid JUnit XML: " + sanitize(ex.getMessage()), ex);
        }
    }

    public static List<FixtureSuiteRunner.CaseOutcome> parseDirectory(Path reportsDir) {
        if (reportsDir == null || !Files.isDirectory(reportsDir)) {
            return List.of();
        }
        Map<String, FixtureSuiteRunner.CaseOutcome> byKey = new LinkedHashMap<>();
        try (Stream<Path> stream = Files.list(reportsDir)) {
            List<Path> xmlFiles = stream
                    .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".xml"))
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .toList();
            for (Path xmlFile : xmlFiles) {
                String content = Files.readString(xmlFile, StandardCharsets.UTF_8);
                for (FixtureSuiteRunner.CaseOutcome outcome : parseXml(content)) {
                    byKey.put(outcome.caseKey(), outcome);
                }
            }
        } catch (Exception ex) {
            throw new IllegalArgumentException("Failed reading JUnit reports under " + reportsDir.getFileName(), ex);
        }
        return List.copyOf(byKey.values());
    }

    public static boolean looksLikeJUnitXml(String content) {
        if (content == null) {
            return false;
        }
        String trimmed = content.trim();
        return trimmed.startsWith("<")
                && (trimmed.contains("<testsuite") || trimmed.contains("<testsuites") || trimmed.contains("<testcase"));
    }

    private static void collectTestcases(Element root, Map<String, FixtureSuiteRunner.CaseOutcome> byKey) {
        if (root == null) {
            return;
        }
        NodeList nodes = root.getElementsByTagName("testcase");
        for (int i = 0; i < nodes.getLength(); i++) {
            Node node = nodes.item(i);
            if (!(node instanceof Element testcase)) {
                continue;
            }
            String classname = attr(testcase, "classname", attr(testcase, "class", "unknown"));
            String name = attr(testcase, "name", "case");
            String key = classname + "#" + name;
            double timeSec = parseDouble(attr(testcase, "time", "0"));
            int durationMs = (int) Math.max(1, Math.round(timeSec * 1000.0));
            StatusMessage status = resolveStatus(testcase);
            byKey.put(key, new FixtureSuiteRunner.CaseOutcome(key, status.status(), durationMs, status.message()));
        }
    }

    private static StatusMessage resolveStatus(Element testcase) {
        Element failure = firstChild(testcase, "failure");
        if (failure != null) {
            return new StatusMessage(TestCaseStatus.FAIL, messageOf(failure));
        }
        Element error = firstChild(testcase, "error");
        if (error != null) {
            return new StatusMessage(TestCaseStatus.ERROR, messageOf(error));
        }
        Element skipped = firstChild(testcase, "skipped");
        if (skipped != null) {
            return new StatusMessage(TestCaseStatus.SKIP, messageOf(skipped));
        }
        return new StatusMessage(TestCaseStatus.PASS, "");
    }

    private static Element firstChild(Element parent, String tag) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child instanceof Element el && tag.equals(el.getTagName())) {
                return el;
            }
        }
        return null;
    }

    private static String messageOf(Element el) {
        String message = attr(el, "message", "");
        if (!message.isBlank()) {
            return sanitize(message);
        }
        String body = el.getTextContent();
        if (body == null || body.isBlank()) {
            return "";
        }
        return sanitize(body.trim());
    }

    private static String attr(Element el, String name, String defaultValue) {
        if (!el.hasAttribute(name)) {
            return defaultValue;
        }
        String value = el.getAttribute(name);
        return value == null || value.isBlank() ? defaultValue : value;
    }

    private static double parseDouble(String raw) {
        try {
            return Double.parseDouble(raw);
        } catch (Exception ex) {
            return 0;
        }
    }

    private static String sanitize(String message) {
        if (message == null) {
            return "";
        }
        String cleaned = message.replaceAll("/[A-Za-z0-9_./-]+", "[path]");
        return cleaned.substring(0, Math.min(cleaned.length(), 400));
    }

    private record StatusMessage(TestCaseStatus status, String message) {
    }

    /** Convenience for unit tests / callers that want a mutable list builder. */
    public static List<FixtureSuiteRunner.CaseOutcome> parseAll(List<String> xmlDocuments) {
        Map<String, FixtureSuiteRunner.CaseOutcome> byKey = new LinkedHashMap<>();
        for (String xml : xmlDocuments) {
            for (FixtureSuiteRunner.CaseOutcome outcome : parseXml(xml)) {
                byKey.put(outcome.caseKey(), outcome);
            }
        }
        return new ArrayList<>(byKey.values());
    }
}
