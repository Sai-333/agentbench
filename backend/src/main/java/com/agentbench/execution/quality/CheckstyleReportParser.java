package com.agentbench.execution.quality;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Parses Checkstyle XML ({@code checkstyle} root with {@code file}/{@code error} nodes). */
public final class CheckstyleReportParser {

    private CheckstyleReportParser() {
    }

    public static QualityToolReport parse(String xml) {
        if (xml == null || xml.isBlank()) {
            return QualityToolReport.stub("checkstyle", "empty_report");
        }
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            secure(factory);
            Document doc = factory.newDocumentBuilder()
                    .parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
            NodeList files = doc.getElementsByTagName("file");
            List<QualityFinding> findings = new ArrayList<>();
            for (int i = 0; i < files.getLength(); i++) {
                Element file = (Element) files.item(i);
                String path = file.getAttribute("name");
                NodeList errors = file.getElementsByTagName("error");
                for (int j = 0; j < errors.getLength(); j++) {
                    Element error = (Element) errors.item(j);
                    int line = parseInt(error.getAttribute("line"), 0);
                    String severity = error.getAttribute("severity");
                    String message = error.getAttribute("message");
                    String source = error.getAttribute("source");
                    String rule = source.isBlank() ? "checkstyle" : source;
                    String fp = "checkstyle|" + rule + "|" + path + "|" + line + "|" + message;
                    findings.add(new QualityFinding("checkstyle", rule, severity, path, line, message, fp));
                }
            }
            return new QualityToolReport("checkstyle", findings, false, "parsed_checkstyle_xml");
        } catch (Exception ex) {
            return QualityToolReport.stub("checkstyle", "parse_error");
        }
    }

    private static void secure(DocumentBuilderFactory factory) throws Exception {
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setExpandEntityReferences(false);
    }

    private static int parseInt(String value, int fallback) {
        try {
            return Integer.parseInt(value);
        } catch (Exception ex) {
            return fallback;
        }
    }
}
