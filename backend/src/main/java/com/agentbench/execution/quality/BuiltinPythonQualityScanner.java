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
 * Lightweight in-process Python heuristics used when ruff is enabled but no report / isolator
 * result is available. Results are real workspace observations (not invented scores).
 */
public final class BuiltinPythonQualityScanner {

    private static final Set<String> SKIP_DIRS = Set.of(
            ".git", "node_modules", ".venv", "venv", "__pycache__",
            "target", "build", "dist", ".tox", ".mypy_cache", ".ruff_cache", ".pytest_cache"
    );

    private static final Pattern PRINT = Pattern.compile("\\bprint\\s*\\(");
    private static final Pattern BARE_EXCEPT = Pattern.compile("except\\s*:");
    private static final Pattern EVAL = Pattern.compile("\\beval\\s*\\(");
    private static final Pattern IMPORT_STAR = Pattern.compile("from\\s+\\S+\\s+import\\s+\\*");
    private static final Pattern TODO = Pattern.compile("\\bTODO\\b|\\bFIXME\\b");

    private BuiltinPythonQualityScanner() {
    }

    public static QualityToolReport scan(Path workspace) {
        List<QualityFinding> findings = new ArrayList<>();
        if (workspace == null || !Files.isDirectory(workspace)) {
            return QualityToolReport.stub(RuffReportParser.TOOL, "workspace_missing");
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
                    if (!lower.endsWith(".py") && !lower.endsWith(".pyi")) {
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
                            if (PRINT.matcher(line).find()) {
                                findings.add(finding("T201", "WARNING", relative, lineNo, "print() usage"));
                            }
                            if (BARE_EXCEPT.matcher(line).find()) {
                                findings.add(finding("E722", "ERROR", relative, lineNo, "bare except"));
                            }
                            if (EVAL.matcher(line).find()) {
                                findings.add(finding("S307", "ERROR", relative, lineNo, "eval() usage"));
                            }
                            if (IMPORT_STAR.matcher(line).find()) {
                                findings.add(finding("F403", "WARNING", relative, lineNo, "star import"));
                            }
                            if (TODO.matcher(line).find()) {
                                findings.add(finding("TD001", "INFO", relative, lineNo, "TODO/FIXME comment"));
                            }
                        }
                    } catch (IOException ignored) {
                        // skip unreadable
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException ex) {
            return QualityToolReport.stub(RuffReportParser.TOOL, "scan_io_error");
        }
        return new QualityToolReport(RuffReportParser.TOOL, findings, false, "builtin_python_heuristics");
    }

    private static QualityFinding finding(String rule, String severity, String path, int line, String message) {
        String fp = RuffReportParser.TOOL + "|" + rule + "|" + path + "|" + line + "|" + message;
        return new QualityFinding(RuffReportParser.TOOL, rule, severity, path, line, message, fp);
    }
}
