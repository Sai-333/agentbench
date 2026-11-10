package com.agentbench.identity.auth;

import com.agentbench.platform.error.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Exchanges authorization codes and fetches user profiles from IdP token/userinfo endpoints.
 * Never logs client secrets or access tokens.
 */
@Component
public class OidcHttpClient {

    private static final Logger log = LoggerFactory.getLogger(OidcHttpClient.class);

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    @Autowired
    public OidcHttpClient(ObjectMapper objectMapper) {
        this(objectMapper, HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build());
    }

    /** Test-friendly constructor. */
    OidcHttpClient(ObjectMapper objectMapper, HttpClient httpClient) {
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
    }

    public OidcUserProfile exchangeAuthorizationCode(
            String providerId,
            OidcProperties.ProviderProperties config,
            String authorizationCode) {
        String accessToken = requestAccessToken(providerId, config, authorizationCode);
        return fetchProfile(providerId, config, accessToken);
    }

    private String requestAccessToken(
            String providerId,
            OidcProperties.ProviderProperties config,
            String authorizationCode) {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", "authorization_code");
        form.put("code", authorizationCode);
        form.put("redirect_uri", config.getRedirectUri());
        form.put("client_id", config.getClientId());
        form.put("client_secret", config.getClientSecret());

        HttpRequest request = HttpRequest.newBuilder(URI.create(config.getTokenUri()))
                .timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(encodeForm(form)))
                .build();

        JsonNode body = sendJson(providerId, "token", request);
        JsonNode tokenNode = body.get("access_token");
        if (tokenNode == null || tokenNode.asText().isBlank()) {
            log.warn("OIDC token response missing access_token for provider={}", providerId);
            throw ApiException.unauthorized("OIDC token exchange failed");
        }
        return tokenNode.asText();
    }

    private OidcUserProfile fetchProfile(
            String providerId,
            OidcProperties.ProviderProperties config,
            String accessToken) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(config.getUserInfoUri()))
                .timeout(Duration.ofSeconds(20))
                .header("Authorization", "Bearer " + accessToken)
                .header("Accept", "application/json")
                .GET()
                .build();
        JsonNode body = sendJson(providerId, "userinfo", request);

        if ("github".equalsIgnoreCase(providerId)) {
            return mapGitHubProfile(providerId, config, accessToken, body);
        }
        return mapOidcUserInfo(providerId, body);
    }

    private OidcUserProfile mapOidcUserInfo(String providerId, JsonNode body) {
        String subject = text(body, "sub");
        String email = text(body, "email");
        String name = firstNonBlank(text(body, "name"), text(body, "preferred_username"), email);
        if (subject == null || subject.isBlank()) {
            throw ApiException.unauthorized("OIDC userinfo missing subject");
        }
        if (email == null || email.isBlank()) {
            log.warn("OIDC userinfo missing email for provider={}", providerId);
            throw ApiException.unauthorized("OIDC account email is required");
        }
        return new OidcUserProfile(subject, email, name == null || name.isBlank() ? email : name);
    }

    private OidcUserProfile mapGitHubProfile(
            String providerId,
            OidcProperties.ProviderProperties config,
            String accessToken,
            JsonNode user) {
        String subject = firstNonBlank(text(user, "id"), text(user, "node_id"));
        if (subject == null || subject.isBlank()) {
            throw ApiException.unauthorized("GitHub user id missing");
        }
        String email = text(user, "email");
        if (email == null || email.isBlank()) {
            email = fetchGitHubPrimaryEmail(providerId, config, accessToken);
        }
        if (email == null || email.isBlank()) {
            throw ApiException.unauthorized("GitHub account email is required");
        }
        String name = firstNonBlank(text(user, "name"), text(user, "login"), email);
        return new OidcUserProfile(subject, email, name);
    }

    private String fetchGitHubPrimaryEmail(
            String providerId,
            OidcProperties.ProviderProperties config,
            String accessToken) {
        String emailUri = config.getEmailUri();
        if (emailUri == null || emailUri.isBlank()) {
            return null;
        }
        HttpRequest request = HttpRequest.newBuilder(URI.create(emailUri))
                .timeout(Duration.ofSeconds(20))
                .header("Authorization", "Bearer " + accessToken)
                .header("Accept", "application/json")
                .GET()
                .build();
        JsonNode body = sendJson(providerId, "emails", request);
        if (!body.isArray()) {
            return null;
        }
        String fallback = null;
        for (JsonNode node : body) {
            String candidate = text(node, "email");
            if (candidate == null || candidate.isBlank()) {
                continue;
            }
            if (node.path("primary").asBoolean(false) && node.path("verified").asBoolean(true)) {
                return candidate;
            }
            if (fallback == null && node.path("verified").asBoolean(true)) {
                fallback = candidate;
            }
        }
        return fallback;
    }

    private JsonNode sendJson(String providerId, String step, HttpRequest request) {
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();
            if (status < 200 || status >= 300) {
                log.warn("OIDC {} call failed provider={} status={}", step, providerId, status);
                throw ApiException.unauthorized("OIDC " + step + " failed");
            }
            return objectMapper.readTree(response.body());
        } catch (ApiException ex) {
            throw ex;
        } catch (IOException | InterruptedException ex) {
            if (ex instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.warn("OIDC {} call error provider={}: {}", step, providerId, ex.toString());
            throw ApiException.unauthorized("OIDC " + step + " failed");
        }
    }

    private static String encodeForm(Map<String, String> form) {
        return form.entrySet().stream()
                .map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8)
                        + "="
                        + URLEncoder.encode(e.getValue() == null ? "" : e.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        return value.asText();
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }
}
