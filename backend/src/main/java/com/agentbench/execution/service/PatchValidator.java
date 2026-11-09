package com.agentbench.execution.service;

import com.agentbench.evaluation.domain.FailureCode;
import com.agentbench.platform.error.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Validates unified diffs: size, path traversal, and allowed path prefixes.
 * Applies patches to a clean workspace for M4 fixture flows.
 */
@Service
public class PatchValidator {

    private static final Pattern DIFF_PATH = Pattern.compile("^(?:--- |\\+\\+\\+ )(?:a/|b/)?(.+)$");
    private static final Pattern HUNK_HEADER = Pattern.compile("^@@\\s+-([0-9]+)(?:,([0-9]+))?\\s+\\+([0-9]+)(?:,([0-9]+))?\\s+@@");
    private static final Set<String> FORBIDDEN_SEGMENTS = Set.of("..", ".git");
    private static final Set<String> FORBIDDEN_SUFFIXES = Set.of(
            ".git/hooks",
            ".git/hooks/pre-commit",
            ".git/hooks/post-checkout",
            ".git/config",
            ".gitmodules"
    );

    public ValidatedPatch validate(String patchText, int maxBytes, List<String> allowedPathPrefixes) {
        if (patchText == null) {
            throw invalid("Patch content is required");
        }
        byte[] bytes = patchText.getBytes(StandardCharsets.UTF_8);
        if (bytes.length == 0) {
            throw invalid("Patch content is empty");
        }
        if (bytes.length > maxBytes) {
            throw invalid("Patch exceeds max size of " + maxBytes + " bytes");
        }
        if (patchText.indexOf('\0') >= 0) {
            throw invalid("Patch contains binary null bytes");
        }

        List<String> paths = extractPaths(patchText);
        if (paths.isEmpty()) {
            throw invalid("Patch does not contain recognizable file paths");
        }
        List<String> allowed = allowedPathPrefixes == null ? List.of() : allowedPathPrefixes;
        for (String rawPath : paths) {
            rejectMaliciousPath(rawPath);
            String path = normalizePath(rawPath);
            if (path.isBlank() || "/dev/null".equals(path)) {
                continue;
            }
            rejectMaliciousPath(path);
            for (String segment : path.split("/")) {
                String lower = segment.toLowerCase(Locale.ROOT);
                if (FORBIDDEN_SEGMENTS.contains(lower)) {
                    throw invalid("Patch path rejected: " + path);
                }
                if (segment.contains("\0")) {
                    throw invalid("Patch path rejected: " + path);
                }
            }
            if (path.startsWith("/") || path.matches("^[A-Za-z]:.*") || path.contains(":")) {
                throw invalid("Absolute patch paths are not allowed: " + path);
            }
            String lowerPath = path.toLowerCase(Locale.ROOT);
            for (String suffix : FORBIDDEN_SUFFIXES) {
                if (lowerPath.equals(suffix) || lowerPath.startsWith(suffix + "/")
                        || lowerPath.contains("/" + suffix + "/") || lowerPath.endsWith("/" + suffix)
                        || lowerPath.contains("/.git/")) {
                    throw invalid("Patch path rejected (vcs/hooks): " + path);
                }
            }
            if (!allowed.isEmpty() && allowed.stream().noneMatch(prefix -> pathEqualsOrUnder(path, prefix))) {
                throw invalid("Patch path not in allowlist: " + path);
            }
        }
        return new ValidatedPatch(patchText, bytes.length, paths);
    }

    /**
     * Rejects traversal encodings, UNC paths, and absolute URIs before normalization.
     */
    static void rejectMaliciousPath(String rawPath) {
        if (rawPath == null) {
            throw invalid("Patch path rejected");
        }
        String trimmed = rawPath.trim();
        String lower = trimmed.toLowerCase(Locale.ROOT);
        if (trimmed.contains("..")
                || trimmed.contains("\\..")
                || trimmed.contains("/..")
                || lower.startsWith("file:")
                || lower.startsWith("\\\\")
                || trimmed.startsWith("~")
                || trimmed.contains("%2e%2e")
                || trimmed.contains("%2E%2E")
                || trimmed.contains("..%2f")
                || trimmed.contains("..%5c")) {
            throw invalid("Patch path traversal rejected: " + trimmed);
        }
        if (lower.contains(".git/hooks") || lower.endsWith(".git/hooks") || lower.contains("/hooks/pre-")) {
            throw invalid("Patch path rejected (git hooks): " + trimmed);
        }
    }

    public void applyUnifiedDiff(Path workspaceRoot, String patchText) {
        Map<String, List<String>> fileLines = new LinkedHashMap<>();
        String currentFile = null;
        List<String> working = null;
        int lineIndex = 0;

        String[] lines = patchText.replace("\r\n", "\n").split("\n", -1);
        for (String line : lines) {
            if (line.startsWith("diff --git ") || line.startsWith("index ") || line.startsWith("new file mode")
                    || line.startsWith("deleted file mode")) {
                continue;
            }
            if (line.startsWith("--- ")) {
                continue;
            }
            if (line.startsWith("+++ ")) {
                String path = line.substring(4).trim();
                if (path.startsWith("b/")) {
                    path = path.substring(2);
                }
                currentFile = normalizePath(path);
                if ("/dev/null".equals(currentFile)) {
                    working = null;
                    continue;
                }
                Path target = safeResolve(workspaceRoot, currentFile);
                try {
                    working = Files.exists(target)
                            ? new ArrayList<>(Files.readAllLines(target, StandardCharsets.UTF_8))
                            : new ArrayList<>();
                } catch (Exception ex) {
                    throw new ApiException(HttpStatus.BAD_REQUEST, FailureCode.PATCH_APPLY_CONFLICT.name(),
                            "Unable to read file for patch apply");
                }
                fileLines.put(currentFile, working);
                lineIndex = 0;
                continue;
            }
            Matcher hunk = HUNK_HEADER.matcher(line);
            if (hunk.matches()) {
                if (working == null) {
                    throw invalid("Hunk without target file");
                }
                int oldStart = Integer.parseInt(hunk.group(1));
                lineIndex = Math.max(0, oldStart - 1);
                continue;
            }
            if (working == null || currentFile == null) {
                continue;
            }
            if (line.startsWith("+")) {
                working.add(lineIndex, line.substring(1));
                lineIndex++;
            } else if (line.startsWith("-")) {
                if (lineIndex >= working.size()) {
                    throw new ApiException(HttpStatus.BAD_REQUEST, FailureCode.PATCH_APPLY_CONFLICT.name(),
                            "Patch conflict while deleting lines in " + currentFile);
                }
                working.remove(lineIndex);
            } else if (line.startsWith(" ")) {
                if (lineIndex >= working.size() || !working.get(lineIndex).equals(line.substring(1))) {
                    // Lenient context: if mismatch, still advance when lengths allow.
                    if (lineIndex >= working.size()) {
                        throw new ApiException(HttpStatus.BAD_REQUEST, FailureCode.PATCH_APPLY_CONFLICT.name(),
                                "Patch context mismatch in " + currentFile);
                    }
                }
                lineIndex++;
            } else if (line.startsWith("\\")) {
                // no newline marker
            }
        }

        if (fileLines.isEmpty()) {
            throw invalid("Patch apply produced no file changes");
        }
        for (Map.Entry<String, List<String>> entry : fileLines.entrySet()) {
            Path target = safeResolve(workspaceRoot, entry.getKey());
            try {
                Files.createDirectories(target.getParent());
                List<String> content = entry.getValue();
                String joined = String.join("\n", content);
                if (!content.isEmpty()) {
                    joined = joined + "\n";
                }
                Files.writeString(target, joined, StandardCharsets.UTF_8);
            } catch (ApiException ex) {
                throw ex;
            } catch (Exception ex) {
                throw new ApiException(HttpStatus.BAD_REQUEST, FailureCode.PATCH_APPLY_CONFLICT.name(),
                        "Failed to write patched file");
            }
        }
    }

    private static Path safeResolve(Path root, String relative) {
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path target = normalizedRoot.resolve(relative).normalize();
        if (!target.startsWith(normalizedRoot)) {
            throw invalid("Patch path escaped workspace: " + relative);
        }
        return target;
    }

    private static List<String> extractPaths(String patchText) {
        List<String> paths = new ArrayList<>();
        for (String line : patchText.replace("\r\n", "\n").split("\n")) {
            Matcher matcher = DIFF_PATH.matcher(line);
            if (matcher.matches()) {
                String path = normalizePath(matcher.group(1).trim());
                if (!"/dev/null".equals(path)) {
                    paths.add(path);
                }
            }
        }
        return paths;
    }

    private static String normalizePath(String path) {
        String cleaned = path.trim();
        if (cleaned.startsWith("a/") || cleaned.startsWith("b/")) {
            cleaned = cleaned.substring(2);
        }
        while (cleaned.startsWith("./")) {
            cleaned = cleaned.substring(2);
        }
        return cleaned.replace('\\', '/');
    }

    private static boolean pathEqualsOrUnder(String path, String prefix) {
        String normalizedPrefix = prefix.replace('\\', '/');
        if (!normalizedPrefix.endsWith("/")) {
            return path.equals(normalizedPrefix) || path.startsWith(normalizedPrefix + "/");
        }
        return path.startsWith(normalizedPrefix)
                || path.equals(normalizedPrefix.substring(0, normalizedPrefix.length() - 1));
    }

    private static ApiException invalid(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, FailureCode.PATCH_INVALID.name(), message);
    }

    public record ValidatedPatch(String text, int byteSize, List<String> paths) {
    }
}
