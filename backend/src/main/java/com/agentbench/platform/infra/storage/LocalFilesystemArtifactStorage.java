package com.agentbench.platform.infra.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Local filesystem placeholder for {@link ArtifactStorage}.
 * Suitable for Compose/dev; not a production object store.
 */
@Component
public class LocalFilesystemArtifactStorage implements ArtifactStorage {

    private static final Logger log = LoggerFactory.getLogger(LocalFilesystemArtifactStorage.class);
    private static final String URI_PREFIX = "file://";

    private final Path root;

    public LocalFilesystemArtifactStorage(
            @Value("${agentbench.artifacts.root:/var/agentbench/artifacts}") String rootPath) throws IOException {
        this.root = Path.of(rootPath).toAbsolutePath().normalize();
        Files.createDirectories(this.root);
        log.info("Local artifact storage root initialized at {}", this.root);
    }

    @Override
    public String store(String key, InputStream content, long contentLength, String contentType) {
        Path target = resolveKey(key);
        try {
            Files.createDirectories(target.getParent());
            try (OutputStream out = Files.newOutputStream(target)) {
                content.transferTo(out);
            }
            return URI_PREFIX + target;
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to store artifact key=" + key, ex);
        }
    }

    @Override
    public Optional<InputStream> open(String storageUri) {
        Path path = pathFromUri(storageUri);
        if (!Files.isRegularFile(path)) {
            return Optional.empty();
        }
        try {
            return Optional.of(Files.newInputStream(path));
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to open artifact uri=" + storageUri, ex);
        }
    }

    @Override
    public void delete(String storageUri) {
        Path path = pathFromUri(storageUri);
        try {
            Files.deleteIfExists(path);
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to delete artifact uri=" + storageUri, ex);
        }
    }

    private Path resolveKey(String key) {
        Path resolved = root.resolve(key).normalize();
        if (!resolved.startsWith(root)) {
            throw new IllegalArgumentException("Artifact key escapes storage root: " + key);
        }
        return resolved;
    }

    private Path pathFromUri(String storageUri) {
        if (storageUri == null || !storageUri.startsWith(URI_PREFIX)) {
            throw new IllegalArgumentException("Unsupported storage URI: " + storageUri);
        }
        Path path = Path.of(storageUri.substring(URI_PREFIX.length())).normalize();
        if (!path.startsWith(root)) {
            throw new IllegalArgumentException("Storage URI outside root: " + storageUri);
        }
        return path;
    }
}
