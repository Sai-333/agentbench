package com.agentbench.platform.audit;

import com.agentbench.platform.infra.storage.ArtifactStorageProperties;
import com.agentbench.platform.infra.storage.S3ObjectLockProperties;
import com.agentbench.platform.infra.storage.S3ObjectLockSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/**
 * Opt-in export of {@code platform.worm_audit_events} to S3 with Object Lock retention.
 *
 * <p>Postgres remains the online WORM store. This job layers an off-box Object Lock evidence
 * path when {@code agentbench.artifacts.s3.object-lock.enabled=true} and
 * {@code apply-to-audit-export=true}. Watermark lives in
 * {@code platform.worm_audit_s3_export_state} so worm rows stay append-only.
 */
@Service
@ConditionalOnProperty(name = "agentbench.artifacts.storage", havingValue = "s3")
public class WormAuditS3ExportService {

    private static final Logger log = LoggerFactory.getLogger(WormAuditS3ExportService.class);

    private final S3Client s3Client;
    private final ArtifactStorageProperties storageProperties;
    private final NamedParameterJdbcTemplate jdbc;

    public WormAuditS3ExportService(
            S3Client artifactS3Client,
            ArtifactStorageProperties storageProperties,
            NamedParameterJdbcTemplate jdbc) {
        this.s3Client = artifactS3Client;
        this.storageProperties = storageProperties;
        this.jdbc = jdbc;
    }

    @Scheduled(fixedDelayString = "${agentbench.artifacts.s3.object-lock.audit-export-interval-ms:60000}")
    public void scheduledExport() {
        exportBatch();
    }

    /**
     * Exports the next batch of worm events. Returns the number of objects put.
     */
    @Transactional
    public int exportBatch() {
        S3ObjectLockProperties lock = storageProperties.getS3().getObjectLock();
        if (!lock.exportsAudit()) {
            return 0;
        }
        long afterId = readWatermark();
        int batchSize = Math.max(1, Math.min(lock.getAuditExportBatchSize(), 5_000));
        List<WormAuditEntry> batch = jdbc.query("""
                        SELECT id, org_id, event_type, event_time, actor, resource_type, resource_id,
                               payload_json::text AS payload_json, prev_hash, entry_hash, created_at
                        FROM platform.worm_audit_events
                        WHERE id > :afterId
                        ORDER BY id ASC
                        LIMIT :limit
                        """,
                new MapSqlParameterSource()
                        .addValue("afterId", afterId)
                        .addValue("limit", batchSize),
                (rs, rowNum) -> new WormAuditEntry(
                        rs.getLong("id"),
                        rs.getObject("org_id", java.util.UUID.class),
                        rs.getString("event_type"),
                        ts(rs.getTimestamp("event_time")),
                        rs.getString("actor"),
                        rs.getString("resource_type"),
                        rs.getString("resource_id"),
                        rs.getString("payload_json"),
                        rs.getString("prev_hash"),
                        rs.getString("entry_hash"),
                        ts(rs.getTimestamp("created_at"))
                ));
        if (batch.isEmpty()) {
            return 0;
        }

        String bucket = S3ObjectLockSupport.resolveAuditBucket(storageProperties);
        String prefix = S3ObjectLockSupport.normalizePrefix(lock.getAuditKeyPrefix());
        Instant now = Instant.now();
        long lastId = afterId;
        int exported = 0;
        for (WormAuditEntry entry : batch) {
            String key = prefix + "events/" + entry.id() + ".json";
            byte[] body = toJson(entry).getBytes(StandardCharsets.UTF_8);
            PutObjectRequest.Builder put = PutObjectRequest.builder()
                    .bucket(bucket)
                    .key(key)
                    .contentType("application/json")
                    .contentLength((long) body.length);
            S3ObjectLockSupport.applyRetention(put, lock, now);
            try {
                s3Client.putObject(put.build(), RequestBody.fromBytes(body));
            } catch (Exception ex) {
                throw new IllegalStateException(
                        "Failed exporting worm audit event id=" + entry.id() + " to s3://" + bucket + "/" + key, ex);
            }
            lastId = entry.id();
            exported++;
        }
        writeWatermark(lastId, exported, now);
        log.info("Exported {} worm audit event(s) to s3://{}/{} through id={}",
                exported, bucket, prefix, lastId);
        return exported;
    }

    private long readWatermark() {
        Long value = jdbc.queryForObject("""
                SELECT last_exported_id FROM platform.worm_audit_s3_export_state WHERE id = 1
                """, new MapSqlParameterSource(), Long.class);
        return value == null ? 0L : value;
    }

    private void writeWatermark(long lastId, int count, Instant at) {
        int updated = jdbc.update("""
                UPDATE platform.worm_audit_s3_export_state
                SET last_exported_id = :lastId,
                    last_export_at = :at,
                    last_export_count = :count,
                    updated_at = :at
                WHERE id = 1
                """,
                new MapSqlParameterSource()
                        .addValue("lastId", lastId)
                        .addValue("count", count)
                        .addValue("at", Timestamp.from(at)));
        if (updated != 1) {
            throw new IllegalStateException("worm_audit_s3_export_state row missing (Flyway V22 required)");
        }
    }

    static String toJson(WormAuditEntry entry) {
        StringBuilder sb = new StringBuilder(256);
        sb.append('{');
        field(sb, "id", Long.toString(entry.id()), false);
        sb.append(',');
        field(sb, "orgId", entry.orgId() == null ? null : entry.orgId().toString(), true);
        sb.append(',');
        field(sb, "eventType", entry.eventType(), true);
        sb.append(',');
        field(sb, "eventTime", entry.eventTime() == null ? null : entry.eventTime().toString(), true);
        sb.append(',');
        field(sb, "actor", entry.actor(), true);
        sb.append(',');
        field(sb, "resourceType", entry.resourceType(), true);
        sb.append(',');
        field(sb, "resourceId", entry.resourceId(), true);
        sb.append(',');
        // payload_json is already JSON text from Postgres — embed raw when it looks like an object/array.
        String payload = entry.payloadJson();
        sb.append("\"payload\":");
        if (payload == null || payload.isBlank()) {
            sb.append("{}");
        } else {
            String trimmed = payload.trim();
            if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
                sb.append(trimmed);
            } else {
                sb.append(quote(trimmed));
            }
        }
        sb.append(',');
        field(sb, "prevHash", entry.prevHash(), true);
        sb.append(',');
        field(sb, "entryHash", entry.entryHash(), true);
        sb.append(',');
        field(sb, "createdAt", entry.createdAt() == null ? null : entry.createdAt().toString(), true);
        sb.append('}');
        return sb.toString();
    }

    private static void field(StringBuilder sb, String name, String value, boolean string) {
        sb.append('"').append(name).append('"').append(':');
        if (value == null) {
            sb.append("null");
        } else if (string) {
            sb.append(quote(value));
        } else {
            sb.append(value);
        }
    }

    private static String quote(String value) {
        String escaped = value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
        return "\"" + escaped + "\"";
    }

    private static Instant ts(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
