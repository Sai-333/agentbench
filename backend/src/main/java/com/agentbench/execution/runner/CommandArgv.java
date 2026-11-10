package com.agentbench.execution.runner;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Safe-ish argv helpers for suite {@code entryCommand} strings.
 * Prefer simple whitespace-split tokens; wrap shell metacharacters in {@code sh -c}.
 */
public final class CommandArgv {

    private CommandArgv() {
    }

    public static List<String> fromEntryOrDefault(String entryCommand, List<String> defaultArgv) {
        if (entryCommand == null || entryCommand.isBlank()) {
            return List.copyOf(defaultArgv);
        }
        return parse(entryCommand);
    }

    public static List<String> parse(String command) {
        String trimmed = command == null ? "" : command.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("empty command");
        }
        assertSafeFragment(trimmed);
        if (needsShell(trimmed)) {
            return List.of("sh", "-c", trimmed);
        }
        List<String> parts = new ArrayList<>();
        for (String part : trimmed.split("\\s+")) {
            if (!part.isBlank()) {
                assertSafeFragment(part);
                parts.add(part);
            }
        }
        if (parts.isEmpty()) {
            throw new IllegalArgumentException("empty command");
        }
        return List.copyOf(parts);
    }

    static boolean needsShell(String command) {
        return command.indexOf('|') >= 0
                || command.indexOf(';') >= 0
                || command.indexOf('&') >= 0
                || command.indexOf('>') >= 0
                || command.indexOf('<') >= 0
                || command.indexOf('`') >= 0
                || command.indexOf('$') >= 0
                || command.indexOf('(') >= 0
                || command.indexOf(')') >= 0
                || command.indexOf('"') >= 0
                || command.indexOf('\'') >= 0;
    }

    static void assertSafeFragment(String fragment) {
        String lower = fragment.toLowerCase(Locale.ROOT);
        if (lower.contains("docker.sock") || lower.contains("--privileged")) {
            throw new IllegalArgumentException("Rejected unsafe suite command fragment");
        }
    }
}
