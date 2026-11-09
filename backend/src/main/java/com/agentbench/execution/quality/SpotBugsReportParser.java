package com.agentbench.execution.quality;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Parses SpotBugs/FindBugs XML ({@code BugCollection}/{@code BugInstance}). */
public final class SpotBugsReportParser {

    private SpotBugsReportParser() {
    }

    public static QualityToolReport parse(String xml) {
        if (xml == null || xml.isBlank()) {
            return QualityToolReport.stub("spotbugs", "empty_report");
        }
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setExpandEntityReferences(false);
            Document doc = factory.newDocumentBuilder()
                    .parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
            NodeList bugs = doc.getElementsByTagName("BugInstance");
            List<QualityFinding> findings = new ArrayList<>();
            for (int i = 0; i < bugs.getLength(); i++) {
                Element bug = (Element) bugs.item(i);
                String type = bug.getAttribute("type");
                String priority = bug.getAttribute("priority");
                String severity = switch (priority) {
                    case "1" -> "ERROR";
                    case "2" -> "WARNING";
                    default -> "INFO";
                };
                String path = "";
                int line = 0;
                NodeList sourceLines = bug.getElementsByTagName("SourceLine");
                if (sourceLines.getLength() > 0) {
                    Element sl = (Element) sourceLines.item(0);
                    path = sl.getAttribute("sourcepath");
                    if (path.isBlank()) {
                        path = sl.getAttribute("classname");
                    }
                    line = parseInt(sl.getAttribute("start"), 0);
                }
                String message = type;
                NodeList messages = bug.getElementsByTagName("ShortMessage");
                if (messages.getLength() > 0 && messages.item(0).getTextContent() != null) {
                    message = messages.item(0).getTextContent().trim();
                }
                String fp = "spotbugs|" + type + "|" + path + "|" + line + "|" + message;
                findings.add(new QualityFinding("spotbugs", type, severity, path, line, message, fp));
            }
            return new QualityToolReport("spotbugs", findings, false, "parsed_spotbugs_xml");
        } catch (Exception ex) {
            return QualityToolReport.stub("spotbugs", "parse_error");
        }
    }

    private static int parseInt(String value, int fallback) {
        try {
            return Integer.parseInt(value);
        } catch (Exception ex) {
            return fallback;
        }
    }
}
