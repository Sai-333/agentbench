package com.agentbench.execution.quality;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Fetches open issues from SonarQube / SonarCloud {@code /api/issues/search}.
 * Tokens are never logged.
 */
@Component
public class SonarQualityClient {

    private static final Logger log = LoggerFactory.getLogger(SonarQualityClient.class);
    private static final int PAGE_SIZE = 100;

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    @Autowired
    public SonarQualityClient(ObjectMapper objectMapper) {
        this(objectMapper, HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build());
    }

    /** Test-friendly constructor. */
    SonarQualityClient(ObjectMapper objectMapper, HttpClient httpClient) {
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
    }

    public QualityToolReport fetchIssues(SonarProperties properties, String branchOverride) {
        if (properties == null || !properties.isApiConfigured()) {
            return QualityToolReport.stub(SonarIssuesParser.TOOL, "sonar_api_unconfigured");
        }
        try {
            List<JsonNode> allIssues = new ArrayList<>();
            int page = 1;
            int total = Integer.MAX_VALUE;
            int max = properties.getMaxIssues();
            while (allIssues.size() < max && (page - 1) * PAGE_SIZE < total) {
                URI uri = buildSearchUri(properties, branchOverride, page, Math.min(PAGE_SIZE, max - allIssues.size()));
                HttpRequest request = HttpRequest.newBuilder(uri)
                        .timeout(Duration.ofSeconds(properties.getTimeoutSec()))
                        .header("Accept", "application/json")
                        .header("Authorization", basicAuth(properties.getToken()))
                        .GET()
                        .build();
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    log.warn("Sonar issues search failed status={} projectKey={}",
                            response.statusCode(), properties.getProjectKey());
                    return QualityToolReport.stub(SonarIssuesParser.TOOL, "sonar_api_http_" + response.statusCode());
                }
                JsonNode body = objectMapper.readTree(response.body());
                total = body.path("total").asInt(body.path("paging").path("total").asInt(0));
                JsonNode issues = body.path("issues");
                if (!issues.isArray() || issues.isEmpty()) {
                    break;
                }
                for (JsonNode issue : issues) {
                    allIssues.add(issue);
                    if (allIssues.size() >= max) {
                        break;
                    }
                }
                if (issues.size() < PAGE_SIZE) {
                    break;
                }
                page++;
            }
            ObjectNode envelope = objectMapper.createObjectNode();
            ArrayNode array = envelope.putArray("issues");
            for (JsonNode issue : allIssues) {
                array.add(issue);
            }
            String notes = "sonar_api_fetch:" + allIssues.size();
            return SonarIssuesParser.parse(
                    objectMapper.writeValueAsString(envelope),
                    properties.getProjectKey(),
                    notes);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return QualityToolReport.stub(SonarIssuesParser.TOOL, "sonar_api_interrupted");
        } catch (Exception ex) {
            log.warn("Sonar issues search error: {}", safeMessage(ex));
            return QualityToolReport.stub(SonarIssuesParser.TOOL, "sonar_api_error");
        }
    }

    private static URI buildSearchUri(
            SonarProperties properties, String branchOverride, int page, int pageSize) {
        Map<String, String> query = new LinkedHashMap<>();
        query.put("componentKeys", properties.getProjectKey());
        query.put("resolved", "false");
        query.put("ps", String.valueOf(Math.max(1, pageSize)));
        query.put("p", String.valueOf(Math.max(1, page)));
        if (properties.getOrganization() != null && !properties.getOrganization().isBlank()) {
            query.put("organization", properties.getOrganization());
        }
        String branch = branchOverride != null && !branchOverride.isBlank()
                ? branchOverride
                : properties.getBranch();
        if (branch != null && !branch.isBlank()) {
            query.put("branch", branch);
        }
        if (properties.getPullRequest() != null && !properties.getPullRequest().isBlank()) {
            query.put("pullRequest", properties.getPullRequest());
        }
        String encoded = query.entrySet().stream()
                .map(e -> urlEncode(e.getKey()) + "=" + urlEncode(e.getValue()))
                .collect(Collectors.joining("&"));
        return URI.create(properties.normalizedBaseUrl() + "/api/issues/search?" + encoded);
    }

    private static String basicAuth(String token) {
        String raw = token + ":";
        return "Basic " + Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String safeMessage(Exception ex) {
        String message = ex.getMessage();
        if (message == null) {
            return ex.getClass().getSimpleName();
        }
        // Avoid echoing tokens if a misconfigured URL somehow embeds them.
        return message.replaceAll("(?i)(token|authorization)=\\S+", "$1=***");
    }
}
