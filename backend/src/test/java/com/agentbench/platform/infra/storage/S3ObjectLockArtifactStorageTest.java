package com.agentbench.platform.infra.storage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.ObjectLockMode;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class S3ObjectLockArtifactStorageTest {

    @Mock
    private S3Client s3Client;

    private ArtifactStorageProperties properties;
    private S3CompatibleArtifactStorage storage;

    @BeforeEach
    void setUp() {
        properties = new ArtifactStorageProperties();
        properties.setStorage("s3");
        properties.setMaxObjectBytes(1024);
        properties.getS3().setBucket("agentbench-artifacts");
        properties.getS3().setKeyPrefix("demo");
        S3ObjectLockProperties lock = properties.getS3().getObjectLock();
        lock.setEnabled(true);
        lock.setMode("COMPLIANCE");
        lock.setRetentionDays(30);
        lock.setApplyToArtifacts(true);
        lock.setApplyToAuditExport(false);
        storage = new S3CompatibleArtifactStorage(s3Client, properties);
    }

    @Test
    void storeAppliesObjectLockAndRejectsOverwrite() {
        when(s3Client.headObject(any(HeadObjectRequest.class)))
                .thenThrow(NoSuchKeyException.builder().message("missing").build());
        when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenReturn(PutObjectResponse.builder().build());

        byte[] payload = "locked".getBytes(StandardCharsets.UTF_8);
        String uri = storage.store(
                "orgs/demo/patch.diff",
                new ByteArrayInputStream(payload),
                payload.length,
                "text/plain");
        assertEquals("s3://agentbench-artifacts/demo/orgs/demo/patch.diff", uri);

        ArgumentCaptor<PutObjectRequest> captor = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(s3Client).putObject(captor.capture(), any(RequestBody.class));
        assertEquals(ObjectLockMode.COMPLIANCE, captor.getValue().objectLockMode());
        Instant until = captor.getValue().objectLockRetainUntilDate();
        assertTrue(until.isAfter(Instant.now().plusSeconds(29L * 24 * 3600)));

        when(s3Client.headObject(any(HeadObjectRequest.class)))
                .thenReturn(HeadObjectResponse.builder().build());
        assertThrows(ObjectLockProtectedException.class, () -> storage.store(
                "orgs/demo/patch.diff",
                new ByteArrayInputStream(payload),
                payload.length,
                "text/plain"));
    }

    @Test
    void deleteAndSweepRefusedWhenObjectLockProtectsArtifacts() {
        assertThrows(ObjectLockProtectedException.class,
                () -> storage.delete("s3://agentbench-artifacts/demo/orgs/demo/patch.diff"));
        verify(s3Client, never()).deleteObject(any(DeleteObjectRequest.class));

        ArtifactStorage.SweepResult sweep = storage.sweepOlderThan(Instant.now().minusSeconds(60));
        assertEquals(0, sweep.deletedObjects());
        verify(s3Client, never()).listObjectsV2(any(ListObjectsV2Request.class));
    }

    @Test
    void governanceModeAcceptedOnPut() {
        properties.getS3().getObjectLock().setMode("GOVERNANCE");
        storage = new S3CompatibleArtifactStorage(s3Client, properties);
        when(s3Client.headObject(any(HeadObjectRequest.class)))
                .thenThrow(NoSuchKeyException.builder().message("missing").build());
        when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenReturn(PutObjectResponse.builder().build());

        byte[] payload = "g".getBytes(StandardCharsets.UTF_8);
        storage.store("a.bin", new ByteArrayInputStream(payload), payload.length, "application/octet-stream");

        ArgumentCaptor<PutObjectRequest> captor = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(s3Client).putObject(captor.capture(), any(RequestBody.class));
        assertEquals(ObjectLockMode.GOVERNANCE, captor.getValue().objectLockMode());
    }
}
