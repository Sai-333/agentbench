package com.agentbench.platform.infra.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Local filesystem {@link ArtifactStorage} for Compose/dev demos (Docker volume).
 * Active when {@code agentbench.artifacts.storage=local} (default).
 */
@Component
@ConditionalOnProperty(name = "agentbench.artifacts.storage", havingValue = "local", matchIfMissing = true)
public class LocalFilesystemArtifactStorage implements ArtifactStorage {

    private static final Logger log = LoggerFactory.getLogger(LocalFilesystemArtifactStorage.class);
    private static final String URI_PREFIX = "file://";

    private final Path root;
    private final long maxObjectBytes;

    @Autowired
    public LocalFilesystemArtifactStorage(ArtifactStorageProperties properties) throws IOException {
        this.root = Path.of(properties.getRoot()).toAbsolutePath().normalize();
        this.maxObjectBytes = properties.getMaxObjectBytes();
        Files.createDirectories(this.root);
        log.info("Local artifact storage root initialized at {} (maxObjectBytes={})", this.root, this.maxObjectBytes);
    }

    /** Test / manual construction. */
    public LocalFilesystemArtifactStorage(String rootPath) throws IOException {
        this.root = Path.of(rootPath).toAbsolutePath().normalize();
        this.maxObjectBytes = 20_971_520L;
        Files.createDirectories(this.root);
    }

    /** Test construction with explicit size ceiling. */
    public LocalFilesystemArtifactStorage(String rootPath, long maxObjectBytes) throws IOException {
        this.root = Path.of(rootPath).toAbsolutePath().normalize();
        this.maxObjectBytes = maxObjectBytes;
        Files.createDirectories(this.root);
    }

    @Override
    public String store(String key, InputStream content, long contentLength, String contentType) {
        enforceSize(contentLength);
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

    @Override
    public SweepResult sweepOlderThan(Instant cutoff) {
        AtomicInteger deleted = new AtomicInteger();
        AtomicLong freed = new AtomicLong();
        try {
            if (!Files.isDirectory(root)) {
                return SweepResult.empty();
            }
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    try {
                        FileTime time = attrs.lastModifiedTime();
                        if (time.toInstant().isBefore(cutoff)) {
                            long size = attrs.size();
                            Files.deleteIfExists(file);
                            deleted.incrementAndGet();
                            freed.addAndGet(size);
                        }
                    } catch (IOException ex) {
                        log.warn("Failed deleting expired local artifact {}: {}", file, ex.getMessage());
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) {
                    try {
                        if (!dir.equals(root) && isEmpty(dir)) {
                            Files.deleteIfExists(dir);
                        }
                    } catch (IOException ignored) {
                        // best effort
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException ex) {
            throw new IllegalStateException("Local artifact retention sweep failed", ex);
        }
        return new SweepResult(deleted.get(), freed.get());
    }

    private void enforceSize(long contentLength) {
        if (maxObjectBytes > 0 && contentLength > maxObjectBytes) {
            throw new IllegalArgumentException(
                    "Artifact exceeds max-object-bytes=" + maxObjectBytes + " contentLength=" + contentLength);
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

    private static boolean isEmpty(Path dir) throws IOException {
        try (var stream = Files.list(dir)) {
            return stream.findAny().isEmpty();
        }
    }
}
