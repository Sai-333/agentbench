package com.agentbench.agents.infra;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Ensures secrets never appear in logs, error messages, or persisted invocation metadata.
 *
 * <p>Pattern coverage is intentionally limited (common API keys, cloud keys, PEM, JWTs,
 * env-style password dumps). Not a full DLP product — see {@code ArtifactSecretScanner}
 * for artifact persist scanning + audit.
 */
public final class SecretRedactor {

    private static final Pattern BEARER = Pattern.compile(
            "(?i)(authorization\\s*[:=]\\s*bearer\\s+)\\S+");
    private static final Pattern API_KEY_HEADER = Pattern.compile(
            "(?i)(x-api-key\\s*[:=]\\s*)\\S+");
    private static final Pattern SK_STYLE = Pattern.compile(
            "\\b(sk-[A-Za-z0-9_\\-]{8,})\\b");
    private static final Pattern XAI_KEY = Pattern.compile(
            "\\b(xai-[A-Za-z0-9_\\-]{8,})\\b");
    private static final Pattern CURSOR_KEY = Pattern.compile(
            "\\b(crsr_[A-Za-z0-9]{8,})\\b");
    private static final Pattern KEY_ASSIGN = Pattern.compile(
            "(?i)(\"(?:api[_-]?key|token|authorization|password|secret)\"\\s*:\\s*\")([^\"]+)(\")");
    private static final Pattern AWS_ACCESS_KEY = Pattern.compile(
            "\\b(AKIA[0-9A-Z]{16})\\b");
    private static final Pattern AWS_SECRET_ASSIGN = Pattern.compile(
            "(?i)(aws[_-]?secret[_-]?(?:access[_-]?)?key\\s*[=:]\\s*)(\\S+)");
    private static final Pattern PRIVATE_KEY_BLOCK = Pattern.compile(
            "-----BEGIN (?:RSA |EC |OPENSSH |DSA |ENCRYPTED )?PRIVATE KEY-----"
                    + "[\\s\\S]*?"
                    + "-----END (?:RSA |EC |OPENSSH |DSA |ENCRYPTED )?PRIVATE KEY-----");
    private static final Pattern JWT = Pattern.compile(
            "\\b(eyJ[A-Za-z0-9_-]{10,}\\.eyJ[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,})\\b");
    private static final Pattern GITHUB_TOKEN = Pattern.compile(
            "\\b(gh[pousr]_[A-Za-z0-9_]{20,}|github_pat_[A-Za-z0-9_]{20,})\\b");
    private static final Pattern SLACK_TOKEN = Pattern.compile(
            "\\b(xox[baprs]-[A-Za-z0-9-]{10,})\\b");
    private static final Pattern ENV_PASSWORD = Pattern.compile(
            "(?i)((?:export\\s+)?(?:password|passwd|pwd|secret|api[_-]?key|access[_-]?token|auth[_-]?token)\\s*[=:]\\s*)(\\S+)");

    private SecretRedactor() {
    }

    public static String redact(String input) {
        if (input == null || input.isBlank()) {
            return input;
        }
        String out = BEARER.matcher(input).replaceAll("$1[REDACTED]");
        out = API_KEY_HEADER.matcher(out).replaceAll("$1[REDACTED]");
        out = SK_STYLE.matcher(out).replaceAll("sk-[REDACTED]");
        out = XAI_KEY.matcher(out).replaceAll("xai-[REDACTED]");
        out = CURSOR_KEY.matcher(out).replaceAll("crsr_[REDACTED]");
        out = KEY_ASSIGN.matcher(out).replaceAll("$1[REDACTED]$3");
        out = AWS_ACCESS_KEY.matcher(out).replaceAll("[REDACTED:aws_access_key]");
        out = AWS_SECRET_ASSIGN.matcher(out).replaceAll("$1[REDACTED]");
        out = PRIVATE_KEY_BLOCK.matcher(out).replaceAll("[REDACTED:private_key]");
        out = JWT.matcher(out).replaceAll("[REDACTED:jwt]");
        out = GITHUB_TOKEN.matcher(out).replaceAll("[REDACTED:github_token]");
        out = SLACK_TOKEN.matcher(out).replaceAll("[REDACTED:slack_token]");
        out = ENV_PASSWORD.matcher(out).replaceAll("$1[REDACTED]");
        return out;
    }

    public static String sanitizeError(String message) {
        String redacted = redact(message);
        if (redacted == null) {
            return "Agent provider error";
        }
        // Drop host filesystem paths that might leak infra.
        redacted = redacted.replaceAll("(?i)/var/agentbench[^\\s]*", "[path]");
        if (redacted.length() > 500) {
            return redacted.substring(0, 500) + "…";
        }
        return redacted;
    }

    public static boolean looksLikeSecretKey(String name) {
        if (name == null) {
            return false;
        }
        String n = name.toLowerCase(Locale.ROOT).replace("-", "").replace("_", "");
        return n.equals("apikey")
                || n.equals("api_key".replace("_", ""))
                || n.equals("openaiapikey")
                || n.equals("xaiapikey")
                || n.equals("cursorapikey")
                || n.equals("accesstoken")
                || n.equals("refreshtoken")
                || n.equals("bearertoken")
                || n.equals("password")
                || n.equals("secret")
                || n.equals("secretkey")
                || n.equals("authorization")
                || n.equals("plaintext");
    }
}
