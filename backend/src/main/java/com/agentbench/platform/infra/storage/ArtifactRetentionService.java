package com.agentbench.platform.infra.storage;

import com.agentbench.agents.infra.SecretRedactor;
import com.agentbench.execution.config.ArtifactRetentionProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
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
 * Retention + cleanup for local artifact storage and evaluation workspaces.
 * Policy is configurable; deletions are audited into {@code platform.artifact_cleanup_runs}.
 */
@Service
public class ArtifactRetentionService {

    private static final Logger log = LoggerFactory.getLogger(ArtifactRetentionService.class);

    private final Path artifactsRoot;
    private final ArtifactRetentionProperties properties;
    private final NamedParameterJdbcTemplate jdbc;

    public ArtifactRetentionService(
            @Value("${agentbench.artifacts.root:/var/agentbench/artifacts}") String root,
            ArtifactRetentionProperties properties,
            NamedParameterJdbcTemplate jdbc) {
        this.artifactsRoot = Path.of(root).toAbsolutePath().normalize();
        this.properties = properties;
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
            if (Files.isDirectory(artifactsRoot)) {
                Files.walkFileTree(artifactsRoot, new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                        try {
                            FileTime time = attrs.lastModifiedTime();
                            if (time.toInstant().isBefore(cutoff)) {
                                long size = attrs.size();
                                Files.deleteIfExists(file);
                                deletedFiles.incrementAndGet();
                                freedBytes.addAndGet(size);
                            }
                        } catch (IOException ex) {
                            log.warn("Failed deleting expired artifact {}: {}", file,
                                    SecretRedactor.sanitizeError(ex.getMessage()));
                        }
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult postVisitDirectory(Path dir, IOException exc) {
                        try {
                            if (!dir.equals(artifactsRoot) && isEmpty(dir)) {
                                Files.deleteIfExists(dir);
                            }
                        } catch (IOException ignored) {
                            // best effort
                        }
                        return FileVisitResult.CONTINUE;
                    }
                });
            }
            message = "cutoff=" + cutoff;
        } catch (Exception ex) {
            status = "FAILED";
            message = SecretRedactor.sanitizeError(ex.getMessage());
            log.warn("Artifact retention sweep failed: {}", message);
        }
        recordRun(started, deletedFiles.get(), freedBytes.get(), status, message);
        log.info("Artifact retention sweep status={} deletedFiles={} freedBytes={}",
                status, deletedFiles.get(), freedBytes.get());
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
}
