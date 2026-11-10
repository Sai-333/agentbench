package com.agentbench.platform.infra.storage;

import com.agentbench.agents.infra.SecretRedactor;
import com.agentbench.execution.config.ArtifactRetentionProperties;
import com.agentbench.platform.rls.RlsContext;
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
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Retention + cleanup for artifact blobs and evaluation workspaces.
 *
 * <p>Each sweep (1) purges expired {@code evaluations.patch_artifacts} blobs via the
 * active {@link ArtifactStorage} and marks DB metadata {@code purged_at}, (2) clears
 * expired {@code agents.agent_invocations.patch_storage_uri} pointers, (3) age-sweeps
 * orphan blobs (local walk or S3 list/delete), and (4) TTL-sweeps local workspaces
 * when using S3. Policy is configurable; runs are audited into
 * {@code platform.artifact_cleanup_runs}.
 */
@Service
public class ArtifactRetentionService {

    private static final Logger log = LoggerFactory.getLogger(ArtifactRetentionService.class);

    /** Cap metadata deletes per sweep to keep each run bounded. */
    static final int METADATA_BATCH_LIMIT = 500;

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
        AtomicInteger deletedMetadata = new AtomicInteger();
        AtomicLong freedBytes = new AtomicLong();
        String status = "SUCCEEDED";
        String message = "";
        if (!properties.isEnabled() || properties.getDays() <= 0) {
            message = "retention disabled or days<=0";
            recordRun(started, 0, 0, 0, "SKIPPED", message);
            return new CleanupStats(0, 0, 0, "SKIPPED", message);
        }
        Instant cutoff = Instant.now().minus(properties.getDays(), ChronoUnit.DAYS);
        try {
            // Cross-tenant metadata purge requires controlled RLS bypass.
            MetadataSweepResult meta = RlsContext.callWithBypass(() -> purgeExpiredMetadata(cutoff));
            deletedFiles.addAndGet(meta.deletedBlobs());
            deletedMetadata.addAndGet(meta.deletedMetadata());
            freedBytes.addAndGet(meta.freedBytes());

            ArtifactStorage.SweepResult blob = artifactStorage.sweepOlderThan(cutoff);
            deletedFiles.addAndGet(blob.deletedObjects());
            freedBytes.addAndGet(blob.freedBytes());

            // Workspaces stay on the local volume even when blobs are in S3.
            if (storageProperties.isS3()) {
                SweepCounters local = sweepLocalPath(artifactsRoot.resolve("workspaces"), cutoff);
                deletedFiles.addAndGet(local.deleted);
                freedBytes.addAndGet(local.freed);
            }

            message = "cutoff=" + cutoff
                    + " storage=" + storageProperties.getStorage()
                    + " metadataPurged=" + deletedMetadata.get();
        } catch (Exception ex) {
            status = "FAILED";
            message = SecretRedactor.sanitizeError(ex.getMessage());
            log.warn("Artifact retention sweep failed: {}", message);
        }
        recordRun(started, deletedFiles.get(), freedBytes.get(), deletedMetadata.get(), status, message);
        log.info("Artifact retention sweep status={} deletedFiles={} deletedMetadata={} freedBytes={} storage={}",
                status, deletedFiles.get(), deletedMetadata.get(), freedBytes.get(), storageProperties.getStorage());
        return new CleanupStats(deletedFiles.get(), freedBytes.get(), deletedMetadata.get(), status, message);
    }

    /**
     * Delete expired patch blobs + mark {@code purged_at}; clear expired invocation URIs.
     * Package-visible for unit tests.
     */
    MetadataSweepResult purgeExpiredMetadata(Instant cutoff) {
        AtomicInteger deletedBlobs = new AtomicInteger();
        AtomicInteger deletedMetadata = new AtomicInteger();
        AtomicLong freedBytes = new AtomicLong();

        List<ExpiredPatchRow> expired = jdbc.query("""
                SELECT id, storage_uri, byte_size
                FROM evaluations.patch_artifacts
                WHERE purged_at IS NULL
                  AND collected_at < :cutoff
                ORDER BY collected_at ASC
                LIMIT :limit
                """,
                new MapSqlParameterSource()
                        .addValue("cutoff", Timestamp.from(cutoff))
                        .addValue("limit", METADATA_BATCH_LIMIT),
                (rs, rowNum) -> new ExpiredPatchRow(
                        rs.getObject("id", UUID.class),
                        rs.getString("storage_uri"),
                        rs.getInt("byte_size")
                ));

        Instant purgedAt = Instant.now();
        for (ExpiredPatchRow row : expired) {
            long freed = deleteBlobQuietly(row.storageUri(), row.byteSize());
            if (freed > 0) {
                deletedBlobs.incrementAndGet();
                freedBytes.addAndGet(freed);
            }
            jdbc.update("""
                    UPDATE evaluations.patch_artifacts
                    SET purged_at = :purgedAt,
                        storage_uri = ''
                    WHERE id = :id
                      AND purged_at IS NULL
                    """,
                    new MapSqlParameterSource()
                            .addValue("id", row.id())
                            .addValue("purgedAt", Timestamp.from(purgedAt)));
            deletedMetadata.incrementAndGet();
        }

        int clearedInvocations = jdbc.update("""
                UPDATE agents.agent_invocations
                SET patch_storage_uri = NULL
                WHERE patch_storage_uri IS NOT NULL
                  AND created_at < :cutoff
                  AND status IN ('SUCCEEDED', 'FAILED', 'TIMED_OUT', 'CANCELLED')
                """,
                new MapSqlParameterSource("cutoff", Timestamp.from(cutoff)));
        deletedMetadata.addAndGet(clearedInvocations);

        return new MetadataSweepResult(deletedBlobs.get(), freedBytes.get(), deletedMetadata.get());
    }

    private long deleteBlobQuietly(String storageUri, int reportedBytes) {
        if (storageUri == null || storageUri.isBlank()) {
            return 0L;
        }
        try {
            artifactStorage.delete(storageUri);
            return Math.max(0L, reportedBytes);
        } catch (Exception ex) {
            // Blob may already be gone from a prior orphan sweep — still mark metadata purged.
            log.debug("Artifact blob delete during TTL GC (continuing metadata purge): {}",
                    SecretRedactor.sanitizeError(ex.getMessage()));
            return 0L;
        }
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

    private void recordRun(Instant started, int deleted, long freed, int deletedMetadata, String status, String message) {
        try {
            jdbc.update("""
                    INSERT INTO platform.artifact_cleanup_runs
                        (started_at, finished_at, deleted_files, freed_bytes, deleted_metadata, status, message)
                    VALUES
                        (:startedAt, :finishedAt, :deleted, :freed, :deletedMetadata, :status, :message)
                    """,
                    new MapSqlParameterSource()
                            .addValue("startedAt", Timestamp.from(started))
                            .addValue("finishedAt", Timestamp.from(Instant.now()))
                            .addValue("deleted", deleted)
                            .addValue("freed", freed)
                            .addValue("deletedMetadata", deletedMetadata)
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

    public record CleanupStats(
            int deletedFiles,
            long freedBytes,
            int deletedMetadata,
            String status,
            String message
    ) {
    }

    record MetadataSweepResult(int deletedBlobs, long freedBytes, int deletedMetadata) {
    }

    private record ExpiredPatchRow(UUID id, String storageUri, int byteSize) {
    }

    private record SweepCounters(int deleted, long freed) {
    }
}
