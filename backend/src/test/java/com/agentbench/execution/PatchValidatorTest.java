package com.agentbench.execution;

import com.agentbench.execution.service.PatchValidator;
import com.agentbench.platform.error.ApiException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PatchValidatorTest {

    private final PatchValidator validator = new PatchValidator();

    @TempDir
    Path temp;

    @Test
    void rejectsPathTraversal() {
        String patch = """
                --- a/../../etc/passwd
                +++ b/../../etc/passwd
                @@ -1 +1 @@
                -x
                +y
                """;
        assertThrows(ApiException.class, () -> validator.validate(patch, 10_000, List.of("src/")));
    }

    @Test
    void rejectsDisallowedPaths() {
        String patch = """
                --- a/secrets/key
                +++ b/secrets/key
                @@ -0,0 +1 @@
                +leak
                """;
        assertThrows(ApiException.class, () -> validator.validate(patch, 10_000, List.of("src/")));
    }

    @Test
    void appliesUnifiedDiffToWorkspace() throws Exception {
        Path file = temp.resolve("src/App.java");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "class App { int x; }\n", StandardCharsets.UTF_8);

        String patch = """
                diff --git a/src/App.java b/src/App.java
                --- a/src/App.java
                +++ b/src/App.java
                @@ -1 +1 @@
                -class App { int x; }
                +class App { int x = 1; }
                """;
        validator.validate(patch, 10_000, List.of("src/"));
        validator.applyUnifiedDiff(temp, patch);
        String content = Files.readString(file);
        assertTrue(content.contains("int x = 1"));
        assertEquals(1, content.lines().count());
    }
}
