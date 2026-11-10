package com.agentbench.execution.quality;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Locale;
import java.util.Set;

/**
 * Lightweight workspace language probe for opt-in quality scanners.
 * Skips common vendor / build directories to keep scans cheap.
 */
public final class WorkspaceLanguageProbe {

    private static final Set<String> SKIP_DIRS = Set.of(
            ".git", ".svn", ".hg",
            "node_modules", ".venv", "venv", "__pycache__",
            "target", "build", "dist", ".tox", ".mypy_cache", ".ruff_cache",
            ".pytest_cache", "coverage", ".idea", ".vscode"
    );

    private WorkspaceLanguageProbe() {
    }

    public static boolean hasPythonSources(Path workspace) {
        return hasExtension(workspace, Set.of(".py", ".pyi"));
    }

    public static boolean hasNodeSources(Path workspace) {
        return hasExtension(workspace, Set.of(".js", ".jsx", ".mjs", ".cjs", ".ts", ".tsx"));
    }

    private static boolean hasExtension(Path workspace, Set<String> extensions) {
        if (workspace == null || !Files.isDirectory(workspace)) {
            return false;
        }
        final boolean[] found = {false};
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
                    String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
                    int dot = name.lastIndexOf('.');
                    if (dot >= 0 && extensions.contains(name.substring(dot))) {
                        found[0] = true;
                        return FileVisitResult.TERMINATE;
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException ignored) {
            return false;
        }
        return found[0];
    }
}
