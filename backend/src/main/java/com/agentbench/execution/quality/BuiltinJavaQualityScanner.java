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
 * In-process Java observations used only when Checkstyle, SpotBugs, and PMD did not run.
 * Findings are labeled {@code builtin_java_heuristics}. They are not those tools' results.
 */
public final class BuiltinJavaQualityScanner {

    public static final String TOOL = "builtin_java_heuristics";

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
            return List.of(QualityToolReport.stub(TOOL, "workspace_missing"));
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
                                checkstyle.add(finding("RegexpSinglelineJava.SystemOut",
                                        "WARNING", relative, lineNo, "System.out usage"));
                            }
                            if (TODO.matcher(line).find()) {
                                checkstyle.add(finding("TodoComment",
                                        "INFO", relative, lineNo, "TODO/FIXME comment"));
                            }
                            Matcher field = PUBLIC_FIELD.matcher(line);
                            if (field.find()) {
                                pmd.add(finding("AvoidPublicFields",
                                        "WARNING", relative, lineNo, "Public field: " + field.group(1)));
                            }
                            if (EMPTY_CATCH.matcher(line).find()) {
                                spotbugs.add(finding("REC_CATCH_EXCEPTION",
                                        "WARNING", relative, lineNo, "Empty catch block"));
                            }
                            if (line.contains("printStackTrace(")) {
                                spotbugs.add(finding("DM_USELESS_THREAD",
                                        "INFO", relative, lineNo, "printStackTrace usage"));
                            }
                        }
                    } catch (IOException ignored) {
                        // skip unreadable files
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException ex) {
            return List.of(QualityToolReport.stub(TOOL, "scan_io_error"));
        }
        List<QualityFinding> findings = new ArrayList<>();
        findings.addAll(checkstyle);
        findings.addAll(pmd);
        findings.addAll(spotbugs);
        return List.of(new QualityToolReport(TOOL, findings, false, TOOL));
    }

    private static QualityFinding finding(
            String rule, String severity, String path, int line, String message) {
        String fp = TOOL + "|" + rule + "|" + path + "|" + line + "|" + message;
        return new QualityFinding(TOOL, rule, severity, path, line, message, fp);
    }
}
