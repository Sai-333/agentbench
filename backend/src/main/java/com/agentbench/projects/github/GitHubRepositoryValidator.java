package com.agentbench.projects.github;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Validates GitHub repository accessibility via the GitHub REST API when a token is available.
 * Tokens come from encrypted org secret refs or optional env fallback — never logged or returned.
 */
@Component
public class GitHubRepositoryValidator {

    private static final Logger log = LoggerFactory.getLogger(GitHubRepositoryValidator.class);
    private static final Pattern GITHUB_URL = Pattern.compile(
            "^(?:https://github\\.com/|git@github\\.com:)(?<owner>[^/]+)/(?<repo>[^/.]+)(?:\\.git)?/?$",
            Pattern.CASE_INSENSITIVE
    );

    private final HttpClient httpClient;
    private final String envToken;
    private final boolean validationEnabled;

    public GitHubRepositoryValidator(
            @Value("${agentbench.github.token:}") String envToken,
            @Value("${agentbench.github.validation-enabled:true}") boolean validationEnabled) {
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        this.envToken = envToken == null ? "" : envToken.trim();
        this.validationEnabled = validationEnabled;
    }

    public ValidationResult validate(String gitUrl, Optional<String> secretToken) {
        if (!validationEnabled) {
            return ValidationResult.skipped("GitHub validation disabled by configuration");
        }

        Matcher matcher = GITHUB_URL.matcher(gitUrl.trim());
        if (!matcher.matches()) {
            // Non-GitHub URLs: accept metadata without live API check.
            return ValidationResult.skipped("Non-GitHub URL; accessibility check skipped");
        }

        String owner = matcher.group("owner");
        String repo = matcher.group("repo");
        String token = secretToken.filter(t -> !t.isBlank()).orElse(envToken);
        if (token.isBlank()) {
            // Prefer real validation when a token is configured; otherwise clear SKIPPED status.
            return ValidationResult.skipped(
                    "No GitHub token configured (set org GIT secret or AGENTBENCH_GITHUB_TOKEN); validation skipped");
        }

        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.github.com/repos/" + owner + "/" + repo))
                    .timeout(Duration.ofSeconds(10))
                    .header("Accept", "application/vnd.github+json")
                    .header("X-GitHub-Api-Version", "2022-11-28")
                    .header("Authorization", "Bearer " + token)
                    .GET();
            HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();
            if (status >= 200 && status < 300) {
                return ValidationResult.valid("Repository accessible");
            }
            if (status == 404 || status == 403) {
                return ValidationResult.invalid("GitHub API returned HTTP " + status + " (repo missing or inaccessible)");
            }
            log.warn("Unexpected GitHub validation status={} for owner={} repo={}", status, owner, repo);
            return ValidationResult.invalid("GitHub API returned HTTP " + status);
        } catch (IOException | InterruptedException ex) {
            if (ex instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.warn("GitHub validation transport failure for owner={} repo={}", owner, repo);
            return ValidationResult.invalid("Unable to reach GitHub API");
        }
    }

    public record ValidationResult(String status, String message) {
        public static ValidationResult valid(String message) {
            return new ValidationResult("VALID", message);
        }

        public static ValidationResult invalid(String message) {
            return new ValidationResult("INVALID", message);
        }

        public static ValidationResult skipped(String message) {
            return new ValidationResult("SKIPPED", message);
        }
    }
}
