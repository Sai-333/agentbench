package com.agentbench.platform.infra.storage;

import com.agentbench.agents.infra.SecretRedactor;
import com.agentbench.execution.config.ArtifactRetentionProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Retention + cleanup for artifact blobs and evaluation workspaces.
 *
 * <p>Blob TTL sweeps are delegated to the active {@link ArtifactStorage}
 * (local filesystem walk or S3 object listing). Evaluation workspaces always
 * live on the local artifacts root and are swept separately when using S3.
 * Policy is configurable; deletions are audited into {@code platform.artifact_cleanup_runs}.
 */
@Service
public class ArtifactRetentionService {

    private static final Logger log = LoggerFactory.getLogger(ArtifactRetentionService.class);

    private final Path artifactsRoot;
    private final ArtifactRetentionProperties properties;
    private final ArtifactStorageProperties storageProperties;
    private final ArtifactStorage artifactStorage;
    private final NamedParameterJdbcTemplate jdbc;

    public ArtifactRetentionService(
            ArtifactStorageProperties storageProperties,
            ArtifactRetentionProperties properties,
            ArtifactStorage artifactStorage,
            NamedParameterJdbcTemplate jdbc) {
        this.artifactsRoot = Path.of(storageProperties.getRoot()).toAbsolutePath().normalize();
        this.properties = properties;
        this.storageProperties = storageProperties;
        this.artifactStorage = artifactStorage;
        this.jdbc = jdbc;
    }

    public long maxLogBytes() {
        return properties.getMaxLogBytes();
    }

    /**
     * Truncate oversized log/report payloads before persistence.
     */
    public String truncateForStorage(String content) {
        if (content == null) {
            return "";
        }
        String redacted = SecretRedactor.redact(content);
        long max = properties.getMaxLogBytes();
        if (max <= 0 || redacted.length() <= max) {
            return redacted;
        }
        return redacted.substring(0, (int) Math.min(Integer.MAX_VALUE, max)) + "\n…[truncated by retention policy]\n";
    }

    @Scheduled(fixedDelayString = "${agentbench.artifacts.retention.sweep-interval-ms:3600000}")
    public void scheduledSweep() {
        sweep();
    }

    public CleanupStats sweep() {
        Instant started = Instant.now();
        AtomicInteger deletedFiles = new AtomicInteger();
        AtomicLong freedBytes = new AtomicLong();
        String status = "SUCCEEDED";
        String message = "";
        if (!properties.isEnabled() || properties.getDays() <= 0) {
            message = "retention disabled or days<=0";
            recordRun(started, 0, 0, "SKIPPED", message);
            return new CleanupStats(0, 0, "SKIPPED", message);
        }
        Instant cutoff = Instant.now().minus(properties.getDays(), ChronoUnit.DAYS);
        try {
            ArtifactStorage.SweepResult blob = artifactStorage.sweepOlderThan(cutoff);
            deletedFiles.addAndGet(blob.deletedObjects());
            freedBytes.addAndGet(blob.freedBytes());

            // Workspaces stay on the local volume even when blobs are in S3.
            if (storageProperties.isS3()) {
                SweepCounters local = sweepLocalPath(artifactsRoot.resolve("workspaces"), cutoff);
                deletedFiles.addAndGet(local.deleted);
                freedBytes.addAndGet(local.freed);
            }

            message = "cutoff=" + cutoff + " storage=" + storageProperties.getStorage();
        } catch (Exception ex) {
            status = "FAILED";
            message = SecretRedactor.sanitizeError(ex.getMessage());
            log.warn("Artifact retention sweep failed: {}", message);
        }
        recordRun(started, deletedFiles.get(), freedBytes.get(), status, message);
        log.info("Artifact retention sweep status={} deletedFiles={} freedBytes={} storage={}",
                status, deletedFiles.get(), freedBytes.get(), storageProperties.getStorage());
        return new CleanupStats(deletedFiles.get(), freedBytes.get(), status, message);
    }

    /**
     * Secure cleanup of a single evaluation workspace tree after terminal state.
     */
    public void cleanupEvaluationWorkspace(Path workspace) {
        if (workspace == null) {
            return;
        }
        Path normalized = workspace.toAbsolutePath().normalize();
        Path workspacesRoot = artifactsRoot.resolve("workspaces").normalize();
        if (!normalized.startsWith(workspacesRoot)) {
            log.warn("Refusing to delete workspace outside artifacts root");
            return;
        }
        try {
            if (!Files.exists(normalized)) {
                return;
            }
            Files.walkFileTree(normalized, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.deleteIfExists(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                    Files.deleteIfExists(dir);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException ex) {
            log.warn("Workspace cleanup failed: {}", SecretRedactor.sanitizeError(ex.getMessage()));
        }
    }

    private SweepCounters sweepLocalPath(Path root, Instant cutoff) {
        AtomicInteger deleted = new AtomicInteger();
        AtomicLong freed = new AtomicLong();
        try {
            if (!Files.isDirectory(root)) {
                return new SweepCounters(0, 0L);
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
                        log.warn("Failed deleting expired workspace file {}: {}", file,
                                SecretRedactor.sanitizeError(ex.getMessage()));
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
            log.warn("Local workspace retention sweep failed: {}",
                    SecretRedactor.sanitizeError(ex.getMessage()));
        }
        return new SweepCounters(deleted.get(), freed.get());
    }

    private void recordRun(Instant started, int deleted, long freed, String status, String message) {
        try {
            jdbc.update("""
                    INSERT INTO platform.artifact_cleanup_runs
                        (started_at, finished_at, deleted_files, freed_bytes, status, message)
                    VALUES
                        (:startedAt, :finishedAt, :deleted, :freed, :status, :message)
                    """,
                    new MapSqlParameterSource()
                            .addValue("startedAt", java.sql.Timestamp.from(started))
                            .addValue("finishedAt", java.sql.Timestamp.from(Instant.now()))
                            .addValue("deleted", deleted)
                            .addValue("freed", freed)
                            .addValue("status", status)
                            .addValue("message", message == null ? "" : message.substring(0, Math.min(message.length(), 500))));
        } catch (Exception ex) {
            // Table may not exist in unit tests without Flyway — log only.
            log.debug("Could not record cleanup run: {}", SecretRedactor.sanitizeError(ex.getMessage()));
        }
    }

    private static boolean isEmpty(Path dir) throws IOException {
        try (var stream = Files.list(dir)) {
            return stream.findAny().isEmpty();
        }
    }

    public record CleanupStats(int deletedFiles, long freedBytes, String status, String message) {
    }

    private record SweepCounters(int deleted, long freed) {
    }
}
