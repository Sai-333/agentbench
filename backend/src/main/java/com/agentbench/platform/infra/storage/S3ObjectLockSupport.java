package com.agentbench.platform.infra.storage;

import software.amazon.awssdk.services.s3.model.ObjectLockMode;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Shared helpers for applying Object Lock retention on PutObject requests.
 */
public final class S3ObjectLockSupport {

    private S3ObjectLockSupport() {
    }

    public static Instant retainUntil(S3ObjectLockProperties lock, Instant now) {
        if (lock == null || !lock.isEnabled()) {
            throw new IllegalStateException("Object Lock is not enabled");
        }
        if (lock.getRetentionDays() <= 0) {
            throw new IllegalStateException("Object Lock retention-days must be > 0");
        }
        return now.plus(lock.getRetentionDays(), ChronoUnit.DAYS);
    }

    public static void applyRetention(PutObjectRequest.Builder put, S3ObjectLockProperties lock, Instant now) {
        Instant until = retainUntil(lock, now);
        ObjectLockMode sdkMode = switch (lock.resolvedMode()) {
            case COMPLIANCE -> ObjectLockMode.COMPLIANCE;
            case GOVERNANCE -> ObjectLockMode.GOVERNANCE;
        };
        put.objectLockMode(sdkMode).objectLockRetainUntilDate(until);
    }

    /**
     * Fail-fast config checks for Object Lock. Call when {@code enabled=true}.
     */
    public static void validateEnabledConfig(ArtifactStorageProperties properties) {
        if (properties == null || !properties.isS3()) {
            throw new IllegalStateException(
                    "S3 Object Lock requires ARTIFACT_STORAGE=s3 (agentbench.artifacts.storage=s3)");
        }
        S3ObjectLockProperties lock = properties.getS3().getObjectLock();
        if (!lock.isEnabled()) {
            return;
        }
        lock.resolvedMode(); // throws if invalid
        if (lock.getRetentionDays() <= 0) {
            throw new IllegalStateException(
                    "agentbench.artifacts.s3.object-lock.retention-days must be > 0 when Object Lock is enabled");
        }
        if (!lock.isApplyToArtifacts() && !lock.isApplyToAuditExport()) {
            throw new IllegalStateException(
                    "S3 Object Lock enabled but neither apply-to-artifacts nor apply-to-audit-export is true");
        }
        if (lock.getAuditExportBatchSize() <= 0) {
            throw new IllegalStateException(
                    "agentbench.artifacts.s3.object-lock.audit-export-batch-size must be > 0");
        }
        if (lock.getAuditExportIntervalMs() <= 0) {
            throw new IllegalStateException(
                    "agentbench.artifacts.s3.object-lock.audit-export-interval-ms must be > 0");
        }
        String endpoint = properties.getS3().getEndpoint();
        if (endpoint == null || endpoint.isBlank()) {
            throw new IllegalStateException(
                    "agentbench.artifacts.s3.endpoint is required when Object Lock is enabled");
        }
    }

    public static String normalizePrefix(String prefix) {
        if (prefix == null || prefix.isBlank()) {
            return "";
        }
        String normalized = prefix.replaceAll("^/+", "").replaceAll("/+$", "");
        return normalized.isEmpty() ? "" : normalized + "/";
    }

    public static String resolveAuditBucket(ArtifactStorageProperties properties) {
        S3ObjectLockProperties lock = properties.getS3().getObjectLock();
        String dedicated = lock.getAuditBucket();
        if (dedicated != null && !dedicated.isBlank()) {
            return dedicated.trim();
        }
        return properties.getS3().getBucket();
    }
}
