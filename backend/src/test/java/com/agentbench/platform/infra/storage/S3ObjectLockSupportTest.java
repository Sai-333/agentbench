package com.agentbench.platform.infra.storage;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.s3.model.ObjectLockMode;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class S3ObjectLockSupportTest {

    @Test
    void applyRetentionSetsComplianceAndRetainUntil() {
        ArtifactStorageProperties properties = enabledProperties();
        S3ObjectLockProperties lock = properties.getS3().getObjectLock();
        Instant now = Instant.parse("2026-01-01T00:00:00Z");

        PutObjectRequest.Builder put = PutObjectRequest.builder().bucket("b").key("k");
        S3ObjectLockSupport.applyRetention(put, lock, now);
        PutObjectRequest built = put.build();

        assertEquals(ObjectLockMode.COMPLIANCE, built.objectLockMode());
        assertEquals(now.plusSeconds(2L * 24 * 3600), built.objectLockRetainUntilDate());
    }

    @Test
    void validateRejectsLocalStorageAndBadMode() {
        ArtifactStorageProperties local = new ArtifactStorageProperties();
        local.setStorage("local");
        local.getS3().getObjectLock().setEnabled(true);
        assertThrows(IllegalStateException.class, () -> S3ObjectLockSupport.validateEnabledConfig(local));

        ArtifactStorageProperties s3 = enabledProperties();
        s3.getS3().getObjectLock().setMode("NOT_A_MODE");
        assertThrows(IllegalArgumentException.class, () -> S3ObjectLockSupport.validateEnabledConfig(s3));

        s3.getS3().getObjectLock().setMode("GOVERNANCE");
        s3.getS3().getObjectLock().setRetentionDays(0);
        assertThrows(IllegalStateException.class, () -> S3ObjectLockSupport.validateEnabledConfig(s3));
    }

    @Test
    void validateRequiresAtLeastOneApplyTarget() {
        ArtifactStorageProperties s3 = enabledProperties();
        s3.getS3().getObjectLock().setApplyToArtifacts(false);
        s3.getS3().getObjectLock().setApplyToAuditExport(false);
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> S3ObjectLockSupport.validateEnabledConfig(s3));
        assertTrue(ex.getMessage().contains("neither apply-to-artifacts"));
    }

    @Test
    void resolveAuditBucketFallsBackToArtifactBucket() {
        ArtifactStorageProperties s3 = enabledProperties();
        assertEquals("agentbench-artifacts", S3ObjectLockSupport.resolveAuditBucket(s3));
        s3.getS3().getObjectLock().setAuditBucket("agentbench-audit-worm");
        assertEquals("agentbench-audit-worm", S3ObjectLockSupport.resolveAuditBucket(s3));
    }

    private static ArtifactStorageProperties enabledProperties() {
        ArtifactStorageProperties properties = new ArtifactStorageProperties();
        properties.setStorage("s3");
        properties.getS3().setEndpoint("http://localhost:9000");
        properties.getS3().setBucket("agentbench-artifacts");
        S3ObjectLockProperties lock = properties.getS3().getObjectLock();
        lock.setEnabled(true);
        lock.setMode("COMPLIANCE");
        lock.setRetentionDays(2);
        lock.setApplyToArtifacts(true);
        lock.setApplyToAuditExport(true);
        return properties;
    }
}
