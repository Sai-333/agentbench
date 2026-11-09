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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lightweight in-process Java heuristics that emit Checkstyle / PMD / SpotBugs-category
 * findings when full toolchains are not installed in the evaluation environment.
 * Results are real workspace observations (not invented scores) and participate in
 * new-vs-pre-existing diffing.
 */
public final class BuiltinJavaQualityScanner {

    private static final Pattern SYSTEM_OUT = Pattern.compile("System\\.out\\.print");
    private static final Pattern EMPTY_CATCH = Pattern.compile("catch\\s*\\([^)]+\\)\\s*\\{\\s*}");
    private static final Pattern TODO = Pattern.compile("\\bTODO\\b|\\bFIXME\\b");
    private static final Pattern PUBLIC_FIELD = Pattern.compile("public\\s+(?!class|interface|enum|record|static\\s+final)[\\w.<>,\\[\\]\\s]+\\s+(\\w+)\\s*;");

    private BuiltinJavaQualityScanner() {
    }

    public static List<QualityToolReport> scan(Path workspace) {
        List<QualityFinding> checkstyle = new ArrayList<>();
        List<QualityFinding> pmd = new ArrayList<>();
        List<QualityFinding> spotbugs = new ArrayList<>();
        if (workspace == null || !Files.isDirectory(workspace)) {
            return List.of(
                    QualityToolReport.stub("checkstyle", "workspace_missing"),
                    QualityToolReport.stub("pmd", "workspace_missing"),
                    QualityToolReport.stub("spotbugs", "workspace_missing")
            );
        }
        try {
            Files.walkFileTree(workspace, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (!file.getFileName().toString().endsWith(".java")) {
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
                            if (SYSTEM_OUT.matcher(line).find()) {
                                checkstyle.add(finding("checkstyle", "RegexpSinglelineJava.SystemOut",
                                        "WARNING", relative, lineNo, "System.out usage", line));
                            }
                            if (TODO.matcher(line).find()) {
                                checkstyle.add(finding("checkstyle", "TodoComment",
                                        "INFO", relative, lineNo, "TODO/FIXME comment", line));
                            }
                            Matcher field = PUBLIC_FIELD.matcher(line);
                            if (field.find()) {
                                pmd.add(finding("pmd", "AvoidPublicFields",
                                        "WARNING", relative, lineNo, "Public field: " + field.group(1), line));
                            }
                            if (EMPTY_CATCH.matcher(line).find()) {
                                spotbugs.add(finding("spotbugs", "REC_CATCH_EXCEPTION",
                                        "WARNING", relative, lineNo, "Empty catch block", line));
                            }
                            if (line.contains("printStackTrace(")) {
                                spotbugs.add(finding("spotbugs", "DM_USELESS_THREAD",
                                        "INFO", relative, lineNo, "printStackTrace usage", line));
                            }
                        }
                    } catch (IOException ignored) {
                        // skip unreadable files
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException ex) {
            return List.of(
                    QualityToolReport.stub("checkstyle", "scan_io_error"),
                    QualityToolReport.stub("pmd", "scan_io_error"),
                    QualityToolReport.stub("spotbugs", "scan_io_error")
            );
        }
        return List.of(
                new QualityToolReport("checkstyle", checkstyle, false, "builtin_java_heuristics"),
                new QualityToolReport("pmd", pmd, false, "builtin_java_heuristics"),
                new QualityToolReport("spotbugs", spotbugs, false, "builtin_java_heuristics")
        );
    }

    private static QualityFinding finding(
            String tool, String rule, String severity, String path, int line, String message, String sourceLine) {
        String fp = tool + "|" + rule + "|" + path + "|" + line + "|" + message;
        return new QualityFinding(tool, rule, severity, path, line, message, fp);
    }
}
