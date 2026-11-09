package com.agentbench.platform.infra.storage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalFilesystemArtifactStorageTest {

    @TempDir
    Path tempDir;

    private LocalFilesystemArtifactStorage storage;

    @BeforeEach
    void setUp() throws Exception {
        storage = new LocalFilesystemArtifactStorage(tempDir.toString());
    }

    @Test
    void storeOpenAndDeleteRoundTrip() throws Exception {
        byte[] payload = "hello-artifact".getBytes(StandardCharsets.UTF_8);
        String uri = storage.store("orgs/demo/patch.diff", new ByteArrayInputStream(payload), payload.length, "text/plain");

        Optional<java.io.InputStream> opened = storage.open(uri);
        assertTrue(opened.isPresent());
        try (var in = opened.get()) {
            assertEquals("hello-artifact", new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }

        storage.delete(uri);
        assertTrue(storage.open(uri).isEmpty());
    }

    @Test
    void rejectsPathTraversal() {
        byte[] payload = "x".getBytes(StandardCharsets.UTF_8);
        assertThrows(IllegalArgumentException.class, () ->
                storage.store("../escape.txt", new ByteArrayInputStream(payload), payload.length, "text/plain"));
    }
}
