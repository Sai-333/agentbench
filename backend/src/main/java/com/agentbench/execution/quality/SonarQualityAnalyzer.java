package com.agentbench.execution.quality;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Optional Sonar quality path: workspace report import first, then Web API fetch when configured.
 * When disabled (default), returns an explicit stub so portfolio honesty stays accurate.
 */
@Component
public class SonarQualityAnalyzer {

    public static final String TOOL = SonarIssuesParser.TOOL;

    private final SonarProperties properties;
    private final SonarQualityClient client;

    public SonarQualityAnalyzer(SonarProperties properties, SonarQualityClient client) {
        this.properties = properties;
        this.client = client;
    }

    /** Disabled analyzer for unit tests that do not wire Spring Sonar beans. */
    public static SonarQualityAnalyzer disabled() {
        SonarProperties props = new SonarProperties();
        props.setEnabled(false);
        return new SonarQualityAnalyzer(props, new SonarQualityClient(new com.fasterxml.jackson.databind.ObjectMapper()));
    }

    public boolean isEnabled() {
        return properties != null && properties.isEnabled();
    }

    public QualityToolReport analyze(Path workspace) {
        if (!isEnabled()) {
            return QualityToolReport.stub(TOOL, "sonar_disabled");
        }

        QualityToolReport imported = tryImportReport(workspace);
        if (imported != null) {
            return imported;
        }

        if (properties.isApiConfigured()) {
            String branch = readOptionalMarker(workspace, ".agentbench/sonar-branch");
            if (branch == null || branch.isBlank()) {
                branch = properties.getBranch();
            }
            return client.fetchIssues(properties, branch);
        }

        return QualityToolReport.stub(TOOL, "sonar_enabled_but_unconfigured");
    }

    private QualityToolReport tryImportReport(Path workspace) {
        if (workspace == null || !Files.isDirectory(workspace)) {
            return null;
        }
        for (String relative : properties.getReportPaths()) {
            if (relative == null || relative.isBlank()) {
                continue;
            }
            Path path = workspace.resolve(relative).normalize();
            if (!path.startsWith(workspace.normalize())) {
                continue; // path traversal guard
            }
            if (!Files.isRegularFile(path)) {
                continue;
            }
            try {
                String json = Files.readString(path, StandardCharsets.UTF_8);
                return SonarIssuesParser.parse(json, properties.getProjectKey(), "imported:" + relative);
            } catch (Exception ex) {
                return QualityToolReport.stub(TOOL, "sonar_report_read_error");
            }
        }
        return null;
    }

    private static String readOptionalMarker(Path workspace, String relative) {
        if (workspace == null || !Files.isDirectory(workspace)) {
            return null;
        }
        try {
            Path path = workspace.resolve(relative).normalize();
            if (!path.startsWith(workspace.normalize()) || !Files.isRegularFile(path)) {
                return null;
            }
            return Files.readString(path, StandardCharsets.UTF_8).trim();
        } catch (Exception ex) {
            return null;
        }
    }
}
