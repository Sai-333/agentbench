package com.agentbench.platform.infra.storage;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration test against MinIO via Testcontainers when Docker is available.
 */
@EnabledIf("com.agentbench.platform.infra.storage.S3CompatibleArtifactStorageMinioTest#dockerAvailable")
class S3CompatibleArtifactStorageMinioTest {

    private static final String ACCESS = "minioadmin";
    private static final String SECRET = "minioadmin";
    private static final String BUCKET = "agentbench-artifacts";

    @SuppressWarnings("resource")
    private static GenericContainer<?> minio;
    private static S3Client s3Client;
    private static S3CompatibleArtifactStorage storage;

    static boolean dockerAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (Throwable ex) {
            return false;
        }
    }

    @BeforeAll
    static void startMinio() {
        minio = new GenericContainer<>(DockerImageName.parse("minio/minio:RELEASE.2024-12-18T13-15-44Z"))
                .withEnv("MINIO_ROOT_USER", ACCESS)
                .withEnv("MINIO_ROOT_PASSWORD", SECRET)
                .withCommand("server", "/data")
                .withExposedPorts(9000)
                .waitingFor(Wait.forHttp("/minio/health/live").forPort(9000).forStatusCode(200)
                        .withStartupTimeout(Duration.ofSeconds(60)));
        minio.start();

        String endpoint = "http://" + minio.getHost() + ":" + minio.getMappedPort(9000);
        s3Client = S3Client.builder()
                .endpointOverride(URI.create(endpoint))
                .region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(ACCESS, SECRET)))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .build();
        s3Client.createBucket(CreateBucketRequest.builder().bucket(BUCKET).build());

        ArtifactStorageProperties properties = new ArtifactStorageProperties();
        properties.setStorage("s3");
        properties.setMaxObjectBytes(1_048_576L);
        properties.getS3().setBucket(BUCKET);
        properties.getS3().setEndpoint(endpoint);
        properties.getS3().setAccessKey(ACCESS);
        properties.getS3().setSecretKey(SECRET);
        properties.getS3().setPathStyle(true);
        properties.getS3().setCreateBucket(false);
        storage = new S3CompatibleArtifactStorage(s3Client, properties);
    }

    @AfterAll
    static void stopMinio() {
        if (s3Client != null) {
            s3Client.close();
        }
        if (minio != null) {
            minio.stop();
        }
    }

    @Test
    void roundTripAndSweepAgainstMinio() throws Exception {
        byte[] payload = "minio-round-trip".getBytes(StandardCharsets.UTF_8);
        String uri = storage.store(
                "orgs/it/patch.diff",
                new ByteArrayInputStream(payload),
                payload.length,
                "text/x-diff");
        assertTrue(uri.startsWith("s3://" + BUCKET + "/"));

        Optional<java.io.InputStream> opened = storage.open(uri);
        assertTrue(opened.isPresent());
        try (var in = opened.get()) {
            assertEquals("minio-round-trip", new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }

        storage.delete(uri);
        assertTrue(storage.open(uri).isEmpty());

        // Fresh object should survive a past cutoff sweep.
        String keepUri = storage.store(
                "orgs/it/keep.txt",
                new ByteArrayInputStream(payload),
                payload.length,
                "text/plain");
        ArtifactStorage.SweepResult result = storage.sweepOlderThan(Instant.now().minus(Duration.ofDays(1)));
        assertEquals(0, result.deletedObjects());
        assertTrue(storage.open(keepUri).isPresent());
        storage.delete(keepUri);
    }
}
