package com.agentbench.execution.quality;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Parses PMD XML ({@code pmd}/{@code file}/{@code violation}). */
public final class PmdReportParser {

    private PmdReportParser() {
    }

    public static QualityToolReport parse(String xml) {
        if (xml == null || xml.isBlank()) {
            return QualityToolReport.stub("pmd", "empty_report");
        }
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setExpandEntityReferences(false);
            Document doc = factory.newDocumentBuilder()
                    .parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
            NodeList files = doc.getElementsByTagName("file");
            List<QualityFinding> findings = new ArrayList<>();
            for (int i = 0; i < files.getLength(); i++) {
                Element file = (Element) files.item(i);
                String path = file.getAttribute("name");
                NodeList violations = file.getElementsByTagName("violation");
                for (int j = 0; j < violations.getLength(); j++) {
                    Element v = (Element) violations.item(j);
                    int line = parseInt(v.getAttribute("beginline"), 0);
                    String rule = attr(v, "rule", "pmd");
                    String priority = attr(v, "priority", "3");
                    String severity = switch (priority) {
                        case "1" -> "ERROR";
                        case "2" -> "WARNING";
                        default -> "INFO";
                    };
                    String message = v.getTextContent() == null ? "" : v.getTextContent().trim();
                    String fp = "pmd|" + rule + "|" + path + "|" + line + "|" + message;
                    findings.add(new QualityFinding("pmd", rule, severity, path, line, message, fp));
                }
            }
            return new QualityToolReport("pmd", findings, false, "parsed_pmd_xml");
        } catch (Exception ex) {
            return QualityToolReport.stub("pmd", "parse_error");
        }
    }

    private static String attr(Element el, String name, String fallback) {
        String v = el.getAttribute(name);
        return v == null || v.isBlank() ? fallback : v;
    }

    private static int parseInt(String value, int fallback) {
        try {
            return Integer.parseInt(value);
        } catch (Exception ex) {
            return fallback;
        }
    }
}
