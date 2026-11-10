package com.agentbench.execution.quality;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Lightweight in-process JS/TS heuristics used when eslint is enabled but no report / isolator
 * result is available. Results are real workspace observations (not invented scores).
 */
public final class BuiltinNodeQualityScanner {

    private static final Set<String> SKIP_DIRS = Set.of(
            ".git", "node_modules", ".venv", "venv", "__pycache__",
            "target", "build", "dist", "coverage", ".next", ".nuxt"
    );

    private static final Pattern CONSOLE = Pattern.compile("\\bconsole\\.(log|debug|info)\\s*\\(");
    private static final Pattern EVAL = Pattern.compile("\\beval\\s*\\(");
    private static final Pattern VAR = Pattern.compile("\\bvar\\s+\\w");
    private static final Pattern LOOSE_EQ = Pattern.compile("(?<![=!<>])={2}(?!=)|!=(?!=)");
    private static final Pattern TODO = Pattern.compile("\\bTODO\\b|\\bFIXME\\b");

    private BuiltinNodeQualityScanner() {
    }

    public static QualityToolReport scan(Path workspace) {
        List<QualityFinding> findings = new ArrayList<>();
        if (workspace == null || !Files.isDirectory(workspace)) {
            return QualityToolReport.stub(EslintReportParser.TOOL, "workspace_missing");
        }
        try {
            Files.walkFileTree(workspace, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    String name = dir.getFileName() == null ? "" : dir.getFileName().toString();
                    if (!dir.equals(workspace) && SKIP_DIRS.contains(name)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    String lower = file.getFileName().toString().toLowerCase(Locale.ROOT);
                    if (!(lower.endsWith(".js") || lower.endsWith(".jsx") || lower.endsWith(".mjs")
                            || lower.endsWith(".cjs") || lower.endsWith(".ts") || lower.endsWith(".tsx"))) {
                        return FileVisitResult.CONTINUE;
                    }
                    String relative = workspace.relativize(file).toString().replace('\\', '/');
                    if (relative.contains("..")) {
                        return FileVisitResult.CONTINUE;
                    }
                    try {
                        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                        for (int i = 0; i < lines.size(); i++) {
                            String line = lines.get(i);
                            int lineNo = i + 1;
                            if (CONSOLE.matcher(line).find()) {
                                findings.add(finding("no-console", "WARNING", relative, lineNo, "console logging"));
                            }
                            if (EVAL.matcher(line).find()) {
                                findings.add(finding("no-eval", "ERROR", relative, lineNo, "eval() usage"));
                            }
                            if (VAR.matcher(line).find()) {
                                findings.add(finding("no-var", "WARNING", relative, lineNo, "var declaration"));
                            }
                            if (LOOSE_EQ.matcher(line).find()) {
                                findings.add(finding("eqeqeq", "WARNING", relative, lineNo, "loose equality (==/!=)"));
                            }
                            if (TODO.matcher(line).find()) {
                                findings.add(finding("no-warning-comments", "INFO", relative, lineNo, "TODO/FIXME comment"));
                            }
                        }
                    } catch (IOException ignored) {
                        // skip unreadable
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException ex) {
            return QualityToolReport.stub(EslintReportParser.TOOL, "scan_io_error");
        }
        return new QualityToolReport(EslintReportParser.TOOL, findings, false, "builtin_node_heuristics");
    }

    private static QualityFinding finding(String rule, String severity, String path, int line, String message) {
        String fp = EslintReportParser.TOOL + "|" + rule + "|" + path + "|" + line + "|" + message;
        return new QualityFinding(EslintReportParser.TOOL, rule, severity, path, line, message, fp);
    }
}
