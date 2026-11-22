package com.agentbench.execution.service;

import com.agentbench.evaluation.domain.FailureCode;
import com.agentbench.platform.error.ApiException;
import com.agentbench.projects.github.GitHubRepositoryValidator;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Checks a pinned github.com commit out into an evaluation workspace.
 * Fixture text stays capped; this tree is the git snapshot, not a pasted fixture.
 * A directory already on disk is not the source of the tree.
 */
@Component
public class GitSnapshotCheckout {

    private static final Logger log = LoggerFactory.getLogger(GitSnapshotCheckout.class);
    private static final long FETCH_TIMEOUT_SECONDS = 180;

    private final String githubToken;

    public GitSnapshotCheckout(@Value("${agentbench.github.token:}") String githubToken) {
        this.githubToken = githubToken == null ? "" : githubToken.trim();
    }

    /**
     * Pin carried on a published task snapshot. Storage URI is recorded for the pin
     * and is not a workspace directory to copy.
     */
    public record GitPin(String cloneUrl, String commitSha) {
    }

    public static GitPin pinFrom(JsonNode task) {
        if (task == null || task.isMissingNode() || task.isNull()) {
            return null;
        }
        JsonNode snap = task.path("repoSnapshot");
        String url = firstGithubUrl(text(snap, "gitUrl"), text(task, "gitUrl"));
        String sha = firstSha(text(snap, "commitSha"), text(task, "baselineCommitSha"));
        if (url == null || sha == null) {
            return null;
        }
        return new GitPin(url, sha);
    }

    /**
     * Replaces {@code workspace} with a checkout of the pinned commit.
     * An existing directory, including an operator preload, is removed first.
     */
    public void checkout(Path workspace, GitPin pin) {
        if (pin == null || GitHubRepositoryValidator.parseGithub(pin.cloneUrl()).isEmpty()) {
            throw workspaceError("Git snapshot must be a github.com owner/repo and a commit SHA");
        }
        if (pin.commitSha() == null || !pin.commitSha().matches("[0-9a-f]{40}")) {
            throw workspaceError("Git snapshot must be a github.com owner/repo and a commit SHA");
        }
        materialize(workspace, pin.cloneUrl(), pin.commitSha());
    }

    void materialize(Path workspace, String cloneUrl, String commitSha) {
        Path emptyConfig = null;
        Path askpass = null;
        try {
            replaceWithEmptyDirectory(workspace);
            emptyConfig = Files.createTempFile("agentbench-gitconfig-", ".empty");
            if (!githubToken.isBlank()) {
                askpass = writeAskpass();
            }
            run(workspace, emptyConfig, askpass, "init", "-q");
            run(workspace, emptyConfig, askpass, "remote", "add", "origin", cloneUrl);
            run(workspace, emptyConfig, askpass,
                    "fetch", "--depth", "1", "--quiet", "origin", commitSha);
            run(workspace, emptyConfig, askpass, "checkout", "--detach", "--quiet", "FETCH_HEAD");
            String head = run(workspace, emptyConfig, askpass, "rev-parse", "HEAD").trim();
            if (!commitSha.equalsIgnoreCase(head)) {
                throw workspaceError("Checked out commit does not match the pinned snapshot");
            }
        } catch (ApiException ex) {
            throw ex;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw workspaceError("Git snapshot checkout interrupted");
        } catch (Exception ex) {
            log.warn("Git snapshot checkout failed");
            throw workspaceError("Failed to check out git snapshot");
        } finally {
            deleteQuietly(emptyConfig);
            deleteQuietly(askpass);
        }
    }

    /**
     * UTF-8 character length of the working tree. Skips {@code .git}, {@code target},
     * and {@code node_modules}. This is the size the fixture-text cap does not apply to.
     */
    public static long treeCharacterCount(Path root) throws IOException {
        if (root == null || !Files.isDirectory(root)) {
            return 0;
        }
        long total = 0;
        try (var walk = Files.walk(root)) {
            for (Path path : walk.filter(Files::isRegularFile).toList()) {
                if (excluded(root, path)) {
                    continue;
                }
                byte[] bytes = Files.readAllBytes(path);
                total += new String(bytes, StandardCharsets.UTF_8).length();
            }
        }
        return total;
    }

    private static boolean excluded(Path root, Path path) {
        Path relative = root.relativize(path);
        for (Path part : relative) {
            String name = part.toString();
            if (".git".equals(name) || "target".equals(name) || "node_modules".equals(name)) {
                return true;
            }
        }
        return false;
    }

    private Path writeAskpass() throws IOException {
        Path script = Files.createTempFile("agentbench-git-askpass-", ".sh");
        // The token stays in the git process environment. The script does not contain it.
        Files.writeString(script, """
                #!/bin/sh
                case "$1" in
                  *sername*) printf '%s\\n' "x-access-token" ;;
                  *) printf '%s\\n' "$AGENTBENCH_GITHUB_TOKEN" ;;
                esac
                """, StandardCharsets.UTF_8);
        if (!script.toFile().setExecutable(true)) {
            throw new IOException("Unable to prepare git askpass");
        }
        return script;
    }

    private String run(Path cwd, Path emptyConfig, Path askpass, String... args)
            throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.add("-c");
        command.add("credential.helper=");
        command.add("-c");
        command.add("safe.directory=*");
        command.addAll(List.of(args));
        Path output = Files.createTempFile("agentbench-git-out-", ".txt");
        try {
            return runToFile(cwd, emptyConfig, askpass, output, command);
        } finally {
            deleteQuietly(output);
        }
    }

    private String runToFile(Path cwd, Path emptyConfig, Path askpass, Path output, List<String> command)
            throws IOException, InterruptedException {
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(cwd.toFile());
        builder.redirectErrorStream(true);
        builder.redirectOutput(output.toFile());
        Map<String, String> env = builder.environment();
        env.keySet().removeIf(key -> key.startsWith("GIT_CONFIG_")
                || "GITHUB_TOKEN".equals(key)
                || "GH_TOKEN".equals(key)
                || "GIT_ASKPASS".equals(key)
                || "SSH_AUTH_SOCK".equals(key));
        env.put("GIT_TERMINAL_PROMPT", "0");
        env.put("GIT_CONFIG_NOSYSTEM", "1");
        env.put("GIT_CONFIG_GLOBAL", emptyConfig.toString());
        env.put("GIT_CONFIG_SYSTEM", emptyConfig.toString());
        env.put("GCM_INTERACTIVE", "never");
        if (askpass != null && !githubToken.isBlank()) {
            env.put("GIT_ASKPASS", askpass.toString());
            env.put("AGENTBENCH_GITHUB_TOKEN", githubToken);
        } else {
            env.remove("AGENTBENCH_GITHUB_TOKEN");
        }
        Process process = builder.start();
        boolean finished = process.waitFor(FETCH_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw workspaceError("Git snapshot checkout timed out");
        }
        String text = sanitize(Files.readString(output, StandardCharsets.UTF_8));
        if (process.exitValue() != 0) {
            log.warn("Git snapshot command failed exit={}", process.exitValue());
            String detail = text.isBlank() ? "Git snapshot checkout failed" : "Git snapshot checkout failed: " + text;
            throw workspaceError(detail);
        }
        return text;
    }

    private static void replaceWithEmptyDirectory(Path workspace) throws IOException {
        if (Files.isSymbolicLink(workspace)) {
            Files.delete(workspace);
        } else if (Files.exists(workspace)) {
            deleteRecursive(workspace);
        }
        Files.createDirectories(workspace);
    }

    private static void deleteRecursive(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (var walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ex) {
                    throw new IllegalStateException(ex);
                }
            });
        }
    }

    private static void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // temp file cleanup is best-effort
        }
    }

    static String sanitize(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String cleaned = text
                .replaceAll("(?i)(authorization:\\s*bearer\\s+)\\S+", "$1[redacted]")
                .replaceAll("(?i)(ghp_|github_pat_|gho_|ghu_|ghs_|ghr_)[A-Za-z0-9_]+", "[redacted]")
                .replaceAll("https://[^\\s/@]+:[^\\s/@]+@", "https://");
        cleaned = cleaned.replaceAll("\\s+", " ").trim();
        if (cleaned.length() > 180) {
            cleaned = cleaned.substring(0, 180);
        }
        return cleaned;
    }

    private static String firstGithubUrl(String... candidates) {
        for (String candidate : candidates) {
            var parsed = GitHubRepositoryValidator.parseGithub(candidate);
            if (parsed.isPresent()) {
                return GitHubRepositoryValidator.httpsCloneUrl(parsed.get());
            }
        }
        return null;
    }

    private static String firstSha(String... candidates) {
        for (String candidate : candidates) {
            if (candidate == null) {
                continue;
            }
            String sha = candidate.trim().toLowerCase(Locale.ROOT);
            if (sha.matches("[0-9a-f]{40}")) {
                return sha;
            }
        }
        return null;
    }

    private static String text(JsonNode node, String field) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        JsonNode child = node.get(field);
        if (child == null || child.isNull()) {
            return null;
        }
        String value = child.asText("").trim();
        return value.isEmpty() ? null : value;
    }

    private static ApiException workspaceError(String message) {
        return new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, FailureCode.WORKSPACE_ERROR.name(), message);
    }
}
