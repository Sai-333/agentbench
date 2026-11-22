package com.agentbench.platform.gpu;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Locates a driver library by file name. The path is not stored on the probe row.
 */
final class GpuLibraries {

    private static final List<Path> FIXED_DIRS = List.of(
            Path.of("/usr/lib/x86_64-linux-gnu"),
            Path.of("/usr/lib/aarch64-linux-gnu"),
            Path.of("/usr/lib64"),
            Path.of("/usr/lib"),
            Path.of("/lib64"),
            Path.of("/lib"),
            Path.of("/usr/local/lib"),
            Path.of("/usr/local/cuda/lib64"),
            Path.of("/usr/lib/wsl/lib"));

    private GpuLibraries() {
    }

    static Path find(String... fileNames) {
        List<Path> dirs = new ArrayList<>(FIXED_DIRS);
        addSplit(dirs, System.getProperty("java.library.path"));
        addSplit(dirs, System.getenv("LD_LIBRARY_PATH"));
        for (Path dir : dirs) {
            for (String name : fileNames) {
                if (name == null || name.isBlank() || name.indexOf('/') >= 0 || name.indexOf('\\') >= 0) {
                    continue;
                }
                Path candidate = dir.resolve(name);
                try {
                    if (Files.isRegularFile(candidate)) {
                        return candidate;
                    }
                } catch (SecurityException ignored) {
                    return null;
                }
            }
        }
        return null;
    }

    private static void addSplit(List<Path> dirs, String raw) {
        if (raw == null || raw.isBlank()) {
            return;
        }
        for (String part : raw.split(":")) {
            if (part.isBlank()) {
                continue;
            }
            dirs.add(Path.of(part));
        }
    }
}
