package com.agentbench.execution.quality;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Optional SonarQube / SonarCloud integration for the quality stage.
 *
 * <p>Disabled by default. When enabled, AgentBench prefers a workspace-local issues JSON
 * report (baseline vs post separation), otherwise fetches open issues from the Web API.
 * Checkstyle / SpotBugs / PMD remain the primary local tools; Sonar is never the sole
 * scoring authority.
 */
@ConfigurationProperties(prefix = "agentbench.quality.sonar")
public class SonarProperties {

    /** Master switch. Default {@code false} for local Compose / CI. */
    private boolean enabled = false;

    /**
     * Sonar server base URL (no trailing slash), e.g. {@code https://sonarcloud.io}
     * or {@code http://localhost:9000}.
     */
    private String baseUrl = "";

    /** User token from env ({@code AGENTBENCH_SONAR_TOKEN}). Never log. */
    private String token = "";

    /** Sonar project key ({@code componentKeys} query param). */
    private String projectKey = "";

    /** SonarCloud organization key (optional; ignored by many self-hosted instances). */
    private String organization = "";

    /** Optional branch filter for {@code /api/issues/search}. */
    private String branch = "";

    /** Optional pull-request key filter. */
    private String pullRequest = "";

    /** HTTP timeout for API calls. */
    private int timeoutSec = 20;

    /** Cap on issues loaded from the API (paginated). */
    private int maxIssues = 500;

    /**
     * Relative workspace paths checked for an issues JSON export before calling the API.
     * Comma-separated in YAML list form.
     */
    private java.util.List<String> reportPaths = java.util.List.of(
            "target/sonar-issues.json",
            "sonar-issues.json",
            ".agentbench/sonar-issues.json"
    );

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl == null ? "" : baseUrl.trim();
    }

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token == null ? "" : token;
    }

    public String getProjectKey() {
        return projectKey;
    }

    public void setProjectKey(String projectKey) {
        this.projectKey = projectKey == null ? "" : projectKey.trim();
    }

    public String getOrganization() {
        return organization;
    }

    public void setOrganization(String organization) {
        this.organization = organization == null ? "" : organization.trim();
    }

    public String getBranch() {
        return branch;
    }

    public void setBranch(String branch) {
        this.branch = branch == null ? "" : branch.trim();
    }

    public String getPullRequest() {
        return pullRequest;
    }

    public void setPullRequest(String pullRequest) {
        this.pullRequest = pullRequest == null ? "" : pullRequest.trim();
    }

    public int getTimeoutSec() {
        return timeoutSec;
    }

    public void setTimeoutSec(int timeoutSec) {
        this.timeoutSec = Math.max(1, timeoutSec);
    }

    public int getMaxIssues() {
        return maxIssues;
    }

    public void setMaxIssues(int maxIssues) {
        this.maxIssues = Math.max(1, maxIssues);
    }

    public java.util.List<String> getReportPaths() {
        return reportPaths;
    }

    public void setReportPaths(java.util.List<String> reportPaths) {
        this.reportPaths = reportPaths == null || reportPaths.isEmpty()
                ? java.util.List.of(
                "target/sonar-issues.json",
                "sonar-issues.json",
                ".agentbench/sonar-issues.json")
                : java.util.List.copyOf(reportPaths);
    }

    /** True when API fetch can run (enabled + URL + token + project key). */
    public boolean isApiConfigured() {
        return enabled
                && baseUrl != null && !baseUrl.isBlank()
                && token != null && !token.isBlank()
                && projectKey != null && !projectKey.isBlank();
    }

    public String normalizedBaseUrl() {
        if (baseUrl == null || baseUrl.isBlank()) {
            return "";
        }
        String url = baseUrl.trim();
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        return url;
    }
}
