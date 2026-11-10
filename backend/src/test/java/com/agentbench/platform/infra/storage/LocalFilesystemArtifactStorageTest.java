package com.agentbench.platform.infra.storage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
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
        storage = new LocalFilesystemArtifactStorage(tempDir.toString(), 1024);
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

    @Test
    void rejectsOversizedObject() {
        byte[] payload = new byte[2048];
        assertThrows(IllegalArgumentException.class, () ->
                storage.store("orgs/demo/big.bin", new ByteArrayInputStream(payload), payload.length, "application/octet-stream"));
    }

    @Test
    void sweepDeletesExpiredFiles() throws Exception {
        byte[] payload = "old".getBytes(StandardCharsets.UTF_8);
        String uri = storage.store("orgs/demo/old.txt", new ByteArrayInputStream(payload), payload.length, "text/plain");
        Path file = Path.of(uri.substring("file://".length()));
        Files.setLastModifiedTime(file, java.nio.file.attribute.FileTime.from(Instant.now().minusSeconds(3600)));

        ArtifactStorage.SweepResult result = storage.sweepOlderThan(Instant.now().minusSeconds(60));
        assertEquals(1, result.deletedObjects());
        assertTrue(storage.open(uri).isEmpty());
    }

    @Test
    void sweepRetainsNonExpiredFiles() throws Exception {
        byte[] payload = "keep".getBytes(StandardCharsets.UTF_8);
        String uri = storage.store("orgs/demo/keep.txt", new ByteArrayInputStream(payload), payload.length, "text/plain");

        ArtifactStorage.SweepResult result = storage.sweepOlderThan(Instant.now().minusSeconds(3600));
        assertEquals(0, result.deletedObjects());
        assertTrue(storage.open(uri).isPresent());
    }
}
