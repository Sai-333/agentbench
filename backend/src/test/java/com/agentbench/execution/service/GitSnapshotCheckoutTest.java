package com.agentbench.execution.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GitSnapshotCheckoutTest {

    private static final String CLICKIT_SHA = "754cfa442c192fbd3b482021a925deacdc7e3258";

    @TempDir
    Path temp;

    @Test
    void pinReadsTheCommitAndIgnoresAStorageUri() throws Exception {
        var task = new ObjectMapper().readTree("""
                {
                  "repoSnapshot": {
                    "gitUrl": "https://github.com/Sai-333/clickit-backend",
                    "commitSha": "%s",
                    "branch": "main",
                    "contentDigest": "digest",
                    "storageUri": "/var/preloaded/clickit-archive"
                  }
                }
                """.formatted(CLICKIT_SHA));

        GitSnapshotCheckout.GitPin pin = GitSnapshotCheckout.pinFrom(task);

        assertEquals("https://github.com/Sai-333/clickit-backend.git", pin.cloneUrl());
        assertEquals(CLICKIT_SHA, pin.commitSha());
        assertFalse(pin.cloneUrl().contains("preloaded"));
    }

    @Test
    void checkoutReplacesAPreloadWithATreePastTheFixtureCap() throws Exception {
        Path origin = temp.resolve("origin");
        Files.createDirectories(origin);
        String sha = commitLargeTree(origin);

        Path workspace = temp.resolve("workspace");
        Files.createDirectories(workspace);
        Files.writeString(workspace.resolve("README.md"), "operator preload\n");

        GitSnapshotCheckout checkout = new GitSnapshotCheckout("");
        checkout.materialize(workspace, origin.toAbsolutePath().toString(), sha);

        assertFalse(Files.exists(workspace.resolve("README.md")));
        assertTrue(Files.exists(workspace.resolve("src/Big.java")));
        assertTrue(GitSnapshotCheckout.treeCharacterCount(workspace) > 500_000);
        assertEquals(sha, head(workspace));
    }

    @Test
    void sanitizeRedactsCredentialMaterial() {
        String cleaned = GitSnapshotCheckout.sanitize(
                "fatal: https://x-access-token:ghp_abcdefghijklmnopqrstuvwxyz123456@github.com/acme/private");
        assertFalse(cleaned.contains("ghp_"));
        assertFalse(cleaned.contains("x-access-token:"));
    }

    private static String commitLargeTree(Path origin) throws Exception {
        git(origin, "init", "-q", "-b", "main");
        String body = "class Big {\n" + "    // ".repeat(120_000) + "\n}\n";
        assertTrue(body.length() > 500_000);
        Files.createDirectories(origin.resolve("src"));
        Files.writeString(origin.resolve("src/Big.java"), body);
        git(origin, "-c", "user.email=test@example.com", "-c", "user.name=test", "add", "src/Big.java");
        git(origin, "-c", "user.email=test@example.com", "-c", "user.name=test", "commit", "-q", "-m", "tree");
        return git(origin, "rev-parse", "HEAD").trim();
    }

    private static String head(Path workspace) throws Exception {
        return git(workspace, "rev-parse", "HEAD").trim();
    }

    private static String git(Path dir, String... args) throws Exception {
        String[] command = new String[args.length + 1];
        command[0] = "git";
        System.arraycopy(args, 0, command, 1, args.length);
        Process process = new ProcessBuilder(command)
                .directory(dir.toFile())
                .redirectErrorStream(true)
                .start();
        byte[] output = process.getInputStream().readAllBytes();
        int exit = process.waitFor();
        String text = new String(output, StandardCharsets.UTF_8);
        if (exit != 0) {
            throw new IllegalStateException(text);
        }
        return text;
    }
}
