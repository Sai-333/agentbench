package com.agentbench.platform.infra.storage;

import com.agentbench.agents.infra.SecretRedactor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Scans artifact text for common secret patterns before/at persist.
 *
 * <p>Findings are redacted or quarantined per config. Audit rows record pattern ids and
 * counts only — never the matched secret. This is not a full DLP product.
 */
@Service
public class ArtifactSecretScanner {

    private static final Logger log = LoggerFactory.getLogger(ArtifactSecretScanner.class);

    private static final List<NamedPattern> BUILTIN = List.of(
            named("bearer", Pattern.compile("(?i)authorization\\s*[:=]\\s*bearer\\s+\\S+")),
            named("api_key_header", Pattern.compile("(?i)x-api-key\\s*[:=]\\s*\\S+")),
            named("openai_sk", Pattern.compile("\\bsk-[A-Za-z0-9_\\-]{8,}\\b")),
            named("json_secret_field", Pattern.compile(
                    "(?i)\"(?:api[_-]?key|token|authorization|password|secret)\"\\s*:\\s*\"[^\"]+\"")),
            named("aws_access_key", Pattern.compile("\\bAKIA[0-9A-Z]{16}\\b")),
            named("aws_secret_assign", Pattern.compile(
                    "(?i)aws[_-]?secret[_-]?(?:access[_-]?)?key\\s*[=:]\\s*\\S+")),
            named("private_key", Pattern.compile(
                    "-----BEGIN (?:RSA |EC |OPENSSH |DSA |ENCRYPTED )?PRIVATE KEY-----"
                            + "[\\s\\S]*?"
                            + "-----END (?:RSA |EC |OPENSSH |DSA |ENCRYPTED )?PRIVATE KEY-----")),
            named("jwt", Pattern.compile(
                    "\\beyJ[A-Za-z0-9_-]{10,}\\.eyJ[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}\\b")),
            named("github_token", Pattern.compile(
                    "\\b(?:gh[pousr]_[A-Za-z0-9_]{20,}|github_pat_[A-Za-z0-9_]{20,})\\b")),
            named("slack_token", Pattern.compile("\\bxox[baprs]-[A-Za-z0-9-]{10,}\\b")),
            named("env_password", Pattern.compile(
                    "(?i)(?:export\\s+)?(?:password|passwd|pwd|secret|api[_-]?key|access[_-]?token|auth[_-]?token)\\s*[=:]\\s*\\S+"))
    );

    private final ArtifactSecretScanProperties properties;
    private final NamedParameterJdbcTemplate jdbc;
    private final List<NamedPattern> additional;

    public ArtifactSecretScanner(
            ArtifactSecretScanProperties properties,
            NamedParameterJdbcTemplate jdbc) {
        this.properties = properties;
        this.jdbc = jdbc;
        this.additional = compileAdditional(properties.getAdditionalPatterns());
    }

    /**
     * Prepare text for persist: redact or quarantine. Call before computing content digests.
     */
    public ScanResult prepareText(String storageKey, String content, String contentType) {
        if (!properties.isEnabled()) {
            return ScanResult.passthrough(content);
        }
        if (content == null || content.isEmpty()) {
            return ScanResult.passthrough(content == null ? "" : content);
        }
        if (!isTextual(contentType)) {
            return ScanResult.passthrough(content);
        }

        List<String> hitIds = detect(content);
        if (hitIds.isEmpty()) {
            return ScanResult.passthrough(content);
        }

        String patternIds = String.join(",", distinct(hitIds));
        int count = hitIds.size();

        if (properties.isQuarantineMode()) {
            recordFinding(storageKey, contentType, count, patternIds, "QUARANTINED",
                    "persist refused; patterns=" + patternIds);
            log.warn("Artifact secret scan QUARANTINED key={} findings={} patterns={}",
                    safeKey(storageKey), count, patternIds);
            throw new ArtifactSecretQuarantinedException(safeKey(storageKey), count, patternIds);
        }

        String redacted = redactAll(content);
        recordFinding(storageKey, contentType, count, patternIds, "REDACTED",
                "stored after redaction; patterns=" + patternIds);
        log.info("Artifact secret scan REDACTED key={} findings={} patterns={}",
                safeKey(storageKey), count, patternIds);
        return new ScanResult(redacted, count, patternIds, true);
    }

    /**
     * Scan UTF-8 (or charset) bytes for secrets; return bytes safe to persist.
     */
    public byte[] prepareBytes(String storageKey, byte[] content, String contentType, Charset charset) {
        if (content == null) {
            return new byte[0];
        }
        if (!properties.isEnabled() || !isTextual(contentType)) {
            return content;
        }
        Charset cs = charset == null ? StandardCharsets.UTF_8 : charset;
        String text = new String(content, cs);
        ScanResult result = prepareText(storageKey, text, contentType);
        if (!result.modified()) {
            return content;
        }
        return result.content().getBytes(cs);
    }

    public byte[] prepareBytes(String storageKey, byte[] content, String contentType) {
        return prepareBytes(storageKey, content, contentType, StandardCharsets.UTF_8);
    }

    List<String> detect(String content) {
        List<String> hits = new ArrayList<>();
        for (NamedPattern np : BUILTIN) {
            collectHits(np, content, hits);
        }
        for (NamedPattern np : additional) {
            collectHits(np, content, hits);
        }
        return hits;
    }

    private static void collectHits(NamedPattern np, String content, List<String> hits) {
        Matcher m = np.pattern().matcher(content);
        while (m.find()) {
            // Skip already-redacted placeholders so a second scan (caller + storage wrapper) is idempotent.
            String matched = m.group();
            if (matched != null && matched.contains("[REDACTED")) {
                continue;
            }
            hits.add(np.id());
        }
    }

    private String redactAll(String content) {
        String out = SecretRedactor.redact(content);
        for (NamedPattern np : additional) {
            out = np.pattern().matcher(out).replaceAll("[REDACTED]");
        }
        return out;
    }

    private void recordFinding(
            String storageKey,
            String contentType,
            int findingCount,
            String patternIds,
            String action,
            String message) {
        try {
            jdbc.update("""
                    INSERT INTO platform.artifact_secret_findings
                        (storage_key, content_type, finding_count, pattern_ids, action, message)
                    VALUES
                        (:storageKey, :contentType, :findingCount, :patternIds, :action, :message)
                    """,
                    new MapSqlParameterSource()
                            .addValue("storageKey", truncate(safeKey(storageKey), 500))
                            .addValue("contentType", truncate(contentType == null ? "" : contentType, 100))
                            .addValue("findingCount", findingCount)
                            .addValue("patternIds", truncate(patternIds, 500))
                            .addValue("action", action)
                            .addValue("message", truncate(message == null ? "" : message, 500)));
        } catch (Exception ex) {
            // Unit tests without Flyway — never fail persist solely because audit insert failed.
            log.debug("Could not record artifact secret finding: {}",
                    SecretRedactor.sanitizeError(ex.getMessage()));
        }
    }

    static boolean isTextual(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return true;
        }
        String ct = contentType.toLowerCase(Locale.ROOT);
        return ct.startsWith("text/")
                || ct.contains("json")
                || ct.contains("xml")
                || ct.contains("yaml")
                || ct.contains("x-diff")
                || ct.contains("javascript")
                || ct.equals("application/x-www-form-urlencoded");
    }

    private static String safeKey(String storageKey) {
        if (storageKey == null || storageKey.isBlank()) {
            return "(unknown)";
        }
        // Keys are operator-controlled paths; still strip anything that looks like an embedded secret.
        return SecretRedactor.redact(storageKey);
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    private static List<String> distinct(List<String> ids) {
        Set<String> set = new LinkedHashSet<>(ids);
        return List.copyOf(set);
    }

    private static NamedPattern named(String id, Pattern pattern) {
        return new NamedPattern(id, pattern);
    }

    private static List<NamedPattern> compileAdditional(List<String> patterns) {
        if (patterns == null || patterns.isEmpty()) {
            return List.of();
        }
        List<NamedPattern> out = new ArrayList<>();
        int i = 0;
        for (String raw : patterns) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            try {
                out.add(new NamedPattern("custom_" + i, Pattern.compile(raw)));
                i++;
            } catch (PatternSyntaxException ex) {
                log.warn("Ignoring invalid agentbench.artifacts.secret-scan.additional-patterns entry: {}",
                        SecretRedactor.sanitizeError(ex.getMessage()));
            }
        }
        return List.copyOf(out);
    }

    public record ScanResult(String content, int findingCount, String patternIds, boolean modified) {
        static ScanResult passthrough(String content) {
            return new ScanResult(content, 0, "", false);
        }
    }

    private record NamedPattern(String id, Pattern pattern) {
    }
}
