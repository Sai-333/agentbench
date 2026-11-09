package com.agentbench.agents.infra;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Ensures secrets never appear in logs, error messages, or persisted invocation metadata.
 */
public final class SecretRedactor {

    private static final Pattern BEARER = Pattern.compile(
            "(?i)(authorization\\s*[:=]\\s*bearer\\s+)\\S+");
    private static final Pattern API_KEY_HEADER = Pattern.compile(
            "(?i)(x-api-key\\s*[:=]\\s*)\\S+");
    private static final Pattern SK_STYLE = Pattern.compile(
            "\\b(sk-[A-Za-z0-9_\\-]{8,})\\b");
    private static final Pattern KEY_ASSIGN = Pattern.compile(
            "(?i)(\"(?:api[_-]?key|token|authorization|password|secret)\"\\s*:\\s*\")([^\"]+)(\")");

    private SecretRedactor() {
    }

    public static String redact(String input) {
        if (input == null || input.isBlank()) {
            return input;
        }
        String out = BEARER.matcher(input).replaceAll("$1[REDACTED]");
        out = API_KEY_HEADER.matcher(out).replaceAll("$1[REDACTED]");
        out = SK_STYLE.matcher(out).replaceAll("sk-[REDACTED]");
        out = KEY_ASSIGN.matcher(out).replaceAll("$1[REDACTED]$3");
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
