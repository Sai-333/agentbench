package com.agentbench.platform.regression;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Turns a JUnit XML report, a pytest-json-report document, or a canonical
 * {@code cases} document into ordered case rows. Passed, failed, and skipped
 * counts are always computed from those rows. A summary object inside the
 * report is ignored.
 *
 * <p>JUnit {@code error} and pytest {@code error} count as failed.
 * Pytest {@code xpassed} counts as passed. Pytest {@code xfailed} counts as skipped.
 */
public final class RegressionReportParser {

    public static final int MAX_CASES = 5000;
    public static final int MAX_NAME_CHARS = 500;
    public static final int MAX_MESSAGE_CHARS = 2000;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private RegressionReportParser() {
    }

    public enum CaseStatus {
        PASSED("passed"),
        FAILED("failed"),
        SKIPPED("skipped");

        private final String wire;

        CaseStatus(String wire) {
            this.wire = wire;
        }

        public String wire() {
            return wire;
        }

        public static CaseStatus fromOutcome(String raw) {
            if (raw == null || raw.isBlank()) {
                throw new IllegalArgumentException("Case status is required");
            }
            String value = raw.trim().toLowerCase(Locale.ROOT);
            return switch (value) {
                case "passed", "pass", "success", "xpassed" -> PASSED;
                case "failed", "failure", "fail", "error" -> FAILED;
                case "skipped", "skip", "xfailed" -> SKIPPED;
                default -> throw new IllegalArgumentException("Unrecognized case status: " + raw.trim());
            };
        }
    }

    public record ParsedCase(String name, CaseStatus status, long durationMs, String message) {
    }

    /** Counts derived only from {@link ParsedCase} rows. */
    public record Summary(int passed, int failed, int skipped) {
        public int caseCount() {
            return passed + failed + skipped;
        }
    }

    public record Hints(
            String gitRepo,
            String gitBranch,
            String gitSha,
            String suiteName,
            Instant startedAt,
            Instant finishedAt
    ) {
        static Hints empty() {
            return new Hints(null, null, null, null, null, null);
        }
    }

    public record ParsedReport(List<ParsedCase> cases, Summary summary, Hints hints) {
    }

    public static Summary summarize(List<ParsedCase> cases) {
        int passed = 0;
        int failed = 0;
        int skipped = 0;
        for (ParsedCase item : cases) {
            switch (item.status()) {
                case PASSED -> passed++;
                case FAILED -> failed++;
                case SKIPPED -> skipped++;
            }
        }
        return new Summary(passed, failed, skipped);
    }

    public static ParsedReport parse(String contentType, String body) {
        if (body == null || body.isBlank()) {
            throw new IllegalArgumentException("Report body is empty");
        }
        String trimmed = body.stripLeading();
        String type = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        if (type.contains("xml") || trimmed.startsWith("<")) {
            return parseXml(body);
        }
        if (type.contains("json") || trimmed.startsWith("{") || trimmed.startsWith("[")) {
            return parseJson(body);
        }
        throw new IllegalArgumentException("Report must be JUnit XML or JSON");
    }

    public static Instant parseInstant(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String text = raw.trim();
        try {
            return Instant.parse(text);
        } catch (DateTimeParseException ignored) {
            // try the next format
        }
        try {
            return OffsetDateTime.parse(text).toInstant();
        } catch (DateTimeParseException ignored) {
            // try the next format
        }
        try {
            return ZonedDateTime.parse(text).toInstant();
        } catch (DateTimeParseException ignored) {
            // try the next format
        }
        String normalized = text.replace(' ', 'T');
        try {
            return LocalDateTime.parse(normalized).toInstant(ZoneOffset.UTC);
        } catch (DateTimeParseException ex) {
            throw new IllegalArgumentException("Unrecognized timestamp: " + text);
        }
    }

    static ParsedReport parseJson(String body) {
        final JsonNode root;
        try {
            root = MAPPER.readTree(body);
        } catch (Exception ex) {
            throw new IllegalArgumentException("Invalid JSON report");
        }
        if (root == null || !root.isObject()) {
            throw new IllegalArgumentException("JSON report must be an object");
        }
        String format = text(root, "reportFormat");
        if (format != null) {
            return switch (format.trim().toLowerCase(Locale.ROOT)) {
                case "junit-xml", "junit", "xml" -> parseXmlEmbedded(root);
                case "pytest-json", "pytest" -> parsePytest(reportObject(root));
                case "cases", "canonical" -> parseCanonical(reportObject(root));
                default -> throw new IllegalArgumentException("Unsupported reportFormat: " + format.trim());
            };
        }
        if (root.has("cases")) {
            return parseCanonical(root);
        }
        if (root.has("tests")) {
            return parsePytest(root);
        }
        if (root.has("report")) {
            return parseReportField(root);
        }
        throw new IllegalArgumentException("JSON report needs cases, tests, or report");
    }

    private static ParsedReport parseReportField(JsonNode root) {
        JsonNode report = root.get("report");
        if (report == null || report.isNull()) {
            throw new IllegalArgumentException("JSON report field is empty");
        }
        if (report.isTextual()) {
            ParsedReport inner = parse(null, report.asText());
            return new ParsedReport(inner.cases(), inner.summary(), mergeHints(hintsFrom(root), inner.hints()));
        }
        if (report.isObject()) {
            ParsedReport inner = parseJsonNode(report);
            return new ParsedReport(inner.cases(), inner.summary(), mergeHints(hintsFrom(root), inner.hints()));
        }
        throw new IllegalArgumentException("JSON report field must be a string or object");
    }

    private static ParsedReport parseJsonNode(JsonNode node) {
        if (node.has("cases")) {
            return parseCanonical(node);
        }
        if (node.has("tests")) {
            return parsePytest(node);
        }
        throw new IllegalArgumentException("JSON report object needs cases or tests");
    }

    private static ParsedReport parseXmlEmbedded(JsonNode root) {
        JsonNode report = root.get("report");
        if (report == null || !report.isTextual() || report.asText().isBlank()) {
            throw new IllegalArgumentException("junit-xml reportFormat requires a string report");
        }
        ParsedReport inner = parseXml(report.asText());
        return new ParsedReport(inner.cases(), inner.summary(), mergeHints(hintsFrom(root), inner.hints()));
    }

    private static JsonNode reportObject(JsonNode root) {
        if (root.has("report") && root.get("report").isObject()) {
            return root.get("report");
        }
        return root;
    }

    static ParsedReport parseXml(String xml) {
        Document doc = parseDocument(xml);
        Element root = doc.getDocumentElement();
        if (root == null) {
            throw new IllegalArgumentException("JUnit XML has no root element");
        }
        String rootName = localName(root.getTagName());
        NodeList testcases = doc.getElementsByTagName("testcase");
        boolean knownRoot = "testsuites".equals(rootName) || "testsuite".equals(rootName);
        if (!knownRoot && testcases.getLength() == 0) {
            throw new IllegalArgumentException("Not a JUnit XML report");
        }
        List<ParsedCase> cases = new ArrayList<>();
        for (int i = 0; i < testcases.getLength(); i++) {
            Node node = testcases.item(i);
            if (node instanceof Element testcase) {
                cases.add(caseFromXml(testcase));
            }
        }
        ensureCaseLimit(cases);
        return new ParsedReport(List.copyOf(cases), summarize(cases), suiteWindow(doc));
    }

    private static ParsedCase caseFromXml(Element testcase) {
        String classname = attr(testcase, "classname");
        String name = attr(testcase, "name");
        String caseName;
        if (classname != null && name != null) {
            caseName = classname + "::" + name;
        } else if (name != null) {
            caseName = name;
        } else if (classname != null) {
            caseName = classname;
        } else {
            caseName = "unnamed";
        }
        Element failure = firstChild(testcase, "failure");
        Element error = firstChild(testcase, "error");
        Element skipped = firstChild(testcase, "skipped");
        CaseStatus status;
        String message;
        if (failure != null) {
            status = CaseStatus.FAILED;
            message = elementMessage(failure);
        } else if (error != null) {
            status = CaseStatus.FAILED;
            message = elementMessage(error);
        } else if (skipped != null) {
            status = CaseStatus.SKIPPED;
            message = elementMessage(skipped);
        } else {
            status = CaseStatus.PASSED;
            message = null;
        }
        return new ParsedCase(limitName(caseName), status, secondsToMillis(attr(testcase, "time")), message);
    }

    private static Hints suiteWindow(Document doc) {
        NodeList suites = doc.getElementsByTagName("testsuite");
        Instant started = null;
        Instant finished = null;
        for (int i = 0; i < suites.getLength(); i++) {
            Node node = suites.item(i);
            if (!(node instanceof Element suite)) {
                continue;
            }
            Instant timestamp = null;
            String rawTimestamp = attr(suite, "timestamp");
            if (rawTimestamp != null) {
                timestamp = parseInstant(rawTimestamp);
            }
            if (timestamp == null) {
                continue;
            }
            long durationMs = secondsToMillis(attr(suite, "time"));
            Instant end = timestamp.plusMillis(durationMs);
            if (started == null || timestamp.isBefore(started)) {
                started = timestamp;
            }
            if (finished == null || end.isAfter(finished)) {
                finished = end;
            }
        }
        return new Hints(null, null, null, null, started, finished);
    }

    private static ParsedReport parsePytest(JsonNode node) {
        JsonNode tests = node.get("tests");
        if (tests == null || !tests.isArray()) {
            throw new IllegalArgumentException("pytest JSON report needs a tests array");
        }
        List<ParsedCase> cases = new ArrayList<>();
        for (JsonNode test : tests) {
            if (test == null || !test.isObject()) {
                throw new IllegalArgumentException("pytest test entry must be an object");
            }
            String name = text(test, "nodeid");
            if (name == null) {
                name = text(test, "name");
            }
            if (name == null) {
                name = "unnamed";
            }
            String outcome = text(test, "outcome");
            if (outcome == null) {
                throw new IllegalArgumentException("Pytest case missing outcome: " + name);
            }
            CaseStatus status = CaseStatus.fromOutcome(outcome);
            String message = status == CaseStatus.PASSED ? null : pytestMessage(test);
            cases.add(new ParsedCase(limitName(name), status, durationMillis(test), message));
        }
        ensureCaseLimit(cases);
        return new ParsedReport(List.copyOf(cases), summarize(cases), hintsFrom(node));
    }

    private static ParsedReport parseCanonical(JsonNode node) {
        JsonNode rows = node.get("cases");
        if (rows == null || !rows.isArray()) {
            throw new IllegalArgumentException("Canonical report needs a cases array");
        }
        List<ParsedCase> cases = new ArrayList<>();
        for (JsonNode row : rows) {
            if (row == null || !row.isObject()) {
                throw new IllegalArgumentException("Case entry must be an object");
            }
            String name = text(row, "name");
            if (name == null) {
                throw new IllegalArgumentException("Case name is required");
            }
            String statusRaw = text(row, "status");
            if (statusRaw == null) {
                throw new IllegalArgumentException("Case status is required: " + name);
            }
            long durationMs = 0;
            if (row.hasNonNull("durationMs") && row.get("durationMs").isNumber()) {
                durationMs = Math.max(0, row.get("durationMs").asLong());
            } else if (row.hasNonNull("duration") && row.get("duration").isNumber()) {
                durationMs = secondsToMillis(row.get("duration").asDouble());
            }
            cases.add(new ParsedCase(
                    limitName(name),
                    CaseStatus.fromOutcome(statusRaw),
                    durationMs,
                    cleanMessage(text(row, "message"))));
        }
        ensureCaseLimit(cases);
        return new ParsedReport(List.copyOf(cases), summarize(cases), hintsFrom(node));
    }

    private static long durationMillis(JsonNode test) {
        if (test.hasNonNull("durationMs") && test.get("durationMs").isNumber()) {
            return Math.max(0, test.get("durationMs").asLong());
        }
        if (test.hasNonNull("duration") && test.get("duration").isNumber()) {
            return secondsToMillis(test.get("duration").asDouble());
        }
        long sum = 0;
        boolean any = false;
        for (String phase : List.of("setup", "call", "teardown")) {
            JsonNode node = test.get(phase);
            if (node != null && node.hasNonNull("duration") && node.get("duration").isNumber()) {
                sum += secondsToMillis(node.get("duration").asDouble());
                any = true;
            }
        }
        return any ? sum : 0;
    }

    private static String pytestMessage(JsonNode test) {
        String fromCall = phaseMessage(test.get("call"));
        if (fromCall != null) {
            return fromCall;
        }
        String fromSetup = phaseMessage(test.get("setup"));
        if (fromSetup != null) {
            return fromSetup;
        }
        return phaseMessage(test);
    }

    private static String phaseMessage(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        JsonNode crash = node.get("crash");
        if (crash != null && crash.isObject()) {
            String message = text(crash, "message");
            if (message != null) {
                return cleanMessage(message);
            }
        }
        JsonNode longrepr = node.get("longrepr");
        if (longrepr == null || longrepr.isNull()) {
            return null;
        }
        if (longrepr.isTextual()) {
            return cleanMessage(longrepr.asText());
        }
        if (longrepr.isObject()) {
            JsonNode reprcrash = longrepr.get("reprcrash");
            if (reprcrash != null && reprcrash.isObject()) {
                String message = text(reprcrash, "message");
                if (message != null) {
                    return cleanMessage(message);
                }
            }
            return cleanMessage(text(longrepr, "message"));
        }
        return null;
    }

    private static Hints hintsFrom(JsonNode node) {
        return new Hints(
                text(node, "gitRepo"),
                text(node, "gitBranch"),
                text(node, "gitSha"),
                text(node, "suiteName"),
                node.hasNonNull("startedAt") ? parseInstant(text(node, "startedAt")) : null,
                node.hasNonNull("finishedAt") ? parseInstant(text(node, "finishedAt")) : null);
    }

    private static Hints mergeHints(Hints primary, Hints fallback) {
        return new Hints(
                firstText(primary.gitRepo(), fallback.gitRepo()),
                firstText(primary.gitBranch(), fallback.gitBranch()),
                firstText(primary.gitSha(), fallback.gitSha()),
                firstText(primary.suiteName(), fallback.suiteName()),
                primary.startedAt() != null ? primary.startedAt() : fallback.startedAt(),
                primary.finishedAt() != null ? primary.finishedAt() : fallback.finishedAt());
    }

    private static void ensureCaseLimit(List<ParsedCase> cases) {
        if (cases.size() > MAX_CASES) {
            throw new IllegalArgumentException("Report has more than " + MAX_CASES + " cases");
        }
    }

    private static Document parseDocument(String xml) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setExpandEntityReferences(false);
            try {
                factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
                factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            } catch (IllegalArgumentException ignored) {
                // Parser does not support JAXP external-access attributes.
            }
            return factory.newDocumentBuilder()
                    .parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        } catch (IllegalArgumentException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalArgumentException("Invalid JUnit XML");
        }
    }

    private static Element firstChild(Element parent, String tag) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child instanceof Element el && tag.equals(localName(el.getTagName()))) {
                return el;
            }
        }
        return null;
    }

    private static String elementMessage(Element el) {
        String message = attr(el, "message");
        if (message == null) {
            String body = el.getTextContent();
            message = body == null ? null : body.trim();
        }
        return cleanMessage(message);
    }

    private static String attr(Element el, String name) {
        if (!el.hasAttribute(name)) {
            return null;
        }
        String value = el.getAttribute(name);
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private static String text(JsonNode node, String field) {
        if (node == null || !node.hasNonNull(field)) {
            return null;
        }
        JsonNode value = node.get(field);
        if (!value.isTextual()) {
            return null;
        }
        String text = value.asText().trim();
        return text.isEmpty() ? null : text;
    }

    private static String firstText(String primary, String fallback) {
        return primary != null && !primary.isBlank() ? primary : fallback;
    }

    private static String limitName(String name) {
        String cleaned = name.replace("\u0000", "").trim();
        if (cleaned.isEmpty()) {
            throw new IllegalArgumentException("Case name is required");
        }
        if (cleaned.length() > MAX_NAME_CHARS) {
            return cleaned.substring(0, MAX_NAME_CHARS);
        }
        return cleaned;
    }

    private static String cleanMessage(String message) {
        if (message == null) {
            return null;
        }
        String cleaned = message.replace("\u0000", "").trim();
        if (cleaned.isEmpty()) {
            return null;
        }
        if (cleaned.length() > MAX_MESSAGE_CHARS) {
            return cleaned.substring(0, MAX_MESSAGE_CHARS);
        }
        return cleaned;
    }

    private static long secondsToMillis(String raw) {
        if (raw == null || raw.isBlank()) {
            return 0;
        }
        try {
            return secondsToMillis(Double.parseDouble(raw));
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    private static long secondsToMillis(double seconds) {
        if (Double.isNaN(seconds) || seconds <= 0) {
            return 0;
        }
        double millis = seconds * 1000.0;
        if (millis >= Long.MAX_VALUE) {
            return Long.MAX_VALUE;
        }
        return Math.round(millis);
    }

    private static String localName(String tag) {
        int colon = tag.lastIndexOf(':');
        return colon >= 0 ? tag.substring(colon + 1) : tag;
    }
}
