package com.agentbench.platform.infra.storage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectLockConfigurationRequest;
import software.amazon.awssdk.services.s3.model.GetObjectLockConfigurationResponse;
import software.amazon.awssdk.services.s3.model.ObjectLockConfiguration;
import software.amazon.awssdk.services.s3.model.ObjectLockEnabled;
import software.amazon.awssdk.services.s3.model.S3Exception;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class S3ObjectLockValidatorTest {

    @Mock
    private S3Client s3Client;

    private ArtifactStorageProperties properties;
    private S3ObjectLockValidator validator;

    @BeforeEach
    void setUp() {
        properties = new ArtifactStorageProperties();
        properties.setStorage("s3");
        properties.getS3().setEndpoint("http://localhost:9000");
        properties.getS3().setBucket("agentbench-artifacts");
        validator = new S3ObjectLockValidator(s3Client, properties);
    }

    @Test
    void disabledSkipsProbe() {
        assertDoesNotThrow(validator::validate);
        verifyNoInteractions(s3Client);
    }

    @Test
    void enabledRequiresObjectLockOnBucket() {
        S3ObjectLockProperties lock = properties.getS3().getObjectLock();
        lock.setEnabled(true);
        lock.setMode("COMPLIANCE");
        lock.setRetentionDays(7);
        lock.setApplyToArtifacts(false);
        lock.setApplyToAuditExport(true);
        lock.setRequireBucketObjectLock(true);

        when(s3Client.getObjectLockConfiguration(any(GetObjectLockConfigurationRequest.class)))
                .thenReturn(GetObjectLockConfigurationResponse.builder()
                        .objectLockConfiguration(ObjectLockConfiguration.builder()
                                .objectLockEnabled(ObjectLockEnabled.ENABLED)
                                .build())
                        .build());

        assertDoesNotThrow(validator::validate);
        verify(s3Client).getObjectLockConfiguration(any(GetObjectLockConfigurationRequest.class));
    }

    @Test
    void failsFastWhenBucketLacksObjectLock() {
        S3ObjectLockProperties lock = properties.getS3().getObjectLock();
        lock.setEnabled(true);
        lock.setMode("COMPLIANCE");
        lock.setRetentionDays(7);
        lock.setApplyToAuditExport(true);
        lock.setRequireBucketObjectLock(true);

        when(s3Client.getObjectLockConfiguration(any(GetObjectLockConfigurationRequest.class)))
                .thenThrow(S3Exception.builder().statusCode(404).message("no lock").build());

        IllegalStateException ex = assertThrows(IllegalStateException.class, validator::validate);
        assertTrue(ex.getMessage().contains("GetObjectLockConfiguration")
                || ex.getMessage().contains("Object Lock"));
    }

    @Test
    void requireBucketFalseSkipsProbeWithWarningPath() {
        S3ObjectLockProperties lock = properties.getS3().getObjectLock();
        lock.setEnabled(true);
        lock.setMode("GOVERNANCE");
        lock.setRetentionDays(1);
        lock.setApplyToArtifacts(true);
        lock.setRequireBucketObjectLock(false);

        assertDoesNotThrow(validator::validate);
        verifyNoInteractions(s3Client);
    }
}
