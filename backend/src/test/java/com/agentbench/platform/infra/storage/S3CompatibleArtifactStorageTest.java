package com.agentbench.platform.infra.storage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Object;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class S3CompatibleArtifactStorageTest {

    @Mock
    private S3Client s3Client;

    private S3CompatibleArtifactStorage storage;

    @BeforeEach
    void setUp() {
        ArtifactStorageProperties properties = new ArtifactStorageProperties();
        properties.setStorage("s3");
        properties.setMaxObjectBytes(1024);
        properties.getS3().setBucket("agentbench-artifacts");
        properties.getS3().setKeyPrefix("demo");
        properties.getS3().setPathStyle(true);
        storage = new S3CompatibleArtifactStorage(s3Client, properties);
    }

    @Test
    void storePutsObjectAndReturnsS3Uri() {
        when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenReturn(PutObjectResponse.builder().build());

        byte[] payload = "hello-s3".getBytes(StandardCharsets.UTF_8);
        String uri = storage.store(
                "orgs/demo/patch.diff",
                new ByteArrayInputStream(payload),
                payload.length,
                "text/plain");

        assertEquals("s3://agentbench-artifacts/demo/orgs/demo/patch.diff", uri);

        ArgumentCaptor<PutObjectRequest> captor = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(s3Client).putObject(captor.capture(), any(RequestBody.class));
        assertEquals("agentbench-artifacts", captor.getValue().bucket());
        assertEquals("demo/orgs/demo/patch.diff", captor.getValue().key());
        assertEquals("text/plain", captor.getValue().contentType());
    }

    @Test
    void openReturnsStream() throws Exception {
        byte[] payload = "payload".getBytes(StandardCharsets.UTF_8);
        ResponseInputStream<GetObjectResponse> response = new ResponseInputStream<>(
                GetObjectResponse.builder().build(),
                AbortableInputStream.create(new ByteArrayInputStream(payload)));
        when(s3Client.getObject(any(GetObjectRequest.class))).thenReturn(response);

        Optional<java.io.InputStream> opened = storage.open("s3://agentbench-artifacts/demo/orgs/demo/patch.diff");
        assertTrue(opened.isPresent());
        try (var in = opened.get()) {
            assertEquals("payload", new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    @Test
    void openMissingKeyReturnsEmpty() {
        when(s3Client.getObject(any(GetObjectRequest.class)))
                .thenThrow(NoSuchKeyException.builder().message("missing").build());
        assertTrue(storage.open("s3://agentbench-artifacts/demo/missing.txt").isEmpty());
    }

    @Test
    void deleteIsIdempotent() {
        storage.delete("s3://agentbench-artifacts/demo/orgs/demo/patch.diff");
        ArgumentCaptor<DeleteObjectRequest> captor = ArgumentCaptor.forClass(DeleteObjectRequest.class);
        verify(s3Client).deleteObject(captor.capture());
        assertEquals("demo/orgs/demo/patch.diff", captor.getValue().key());
    }

    @Test
    void rejectsPathTraversalAndOversize() {
        byte[] payload = "x".getBytes(StandardCharsets.UTF_8);
        assertThrows(IllegalArgumentException.class, () ->
                storage.store("../escape.txt", new ByteArrayInputStream(payload), payload.length, "text/plain"));

        byte[] big = new byte[2048];
        assertThrows(IllegalArgumentException.class, () ->
                storage.store("orgs/demo/big.bin", new ByteArrayInputStream(big), big.length, "application/octet-stream"));
    }

    @Test
    void sweepDeletesExpiredObjects() {
        S3Object expired = S3Object.builder()
                .key("demo/old.txt")
                .size(11L)
                .lastModified(Instant.now().minusSeconds(3600))
                .build();
        S3Object fresh = S3Object.builder()
                .key("demo/new.txt")
                .size(5L)
                .lastModified(Instant.now())
                .build();
        when(s3Client.listObjectsV2(any(ListObjectsV2Request.class)))
                .thenReturn(ListObjectsV2Response.builder()
                        .contents(List.of(expired, fresh))
                        .isTruncated(false)
                        .build());

        ArtifactStorage.SweepResult result = storage.sweepOlderThan(Instant.now().minusSeconds(60));
        assertEquals(1, result.deletedObjects());
        assertEquals(11L, result.freedBytes());
        verify(s3Client).deleteObject(DeleteObjectRequest.builder()
                .bucket("agentbench-artifacts")
                .key("demo/old.txt")
                .build());
    }
}
