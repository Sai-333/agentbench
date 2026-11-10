package com.agentbench.platform.infra.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.net.URI;

@Configuration
@EnableConfigurationProperties({ArtifactStorageProperties.class, ArtifactSecretScanProperties.class})
public class ArtifactStorageConfig {

    private static final Logger log = LoggerFactory.getLogger(ArtifactStorageConfig.class);

    /**
     * Primary {@link ArtifactStorage}: wraps local or S3 with secret scanning at persist.
     * TTL/GC and open/delete delegate unchanged.
     */
    @Bean
    @Primary
    ArtifactStorage artifactStorage(
            ObjectProvider<LocalFilesystemArtifactStorage> local,
            ObjectProvider<S3CompatibleArtifactStorage> s3,
            ArtifactSecretScanner scanner) {
        ArtifactStorage delegate = s3.getIfAvailable();
        if (delegate == null) {
            delegate = local.getObject();
        }
        log.info("Artifact storage scanning wrapper enabled over {}", delegate.getClass().getSimpleName());
        return new ScanningArtifactStorage(delegate, scanner);
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(name = "agentbench.artifacts.storage", havingValue = "s3")
    S3Client artifactS3Client(ArtifactStorageProperties properties) {
        ArtifactStorageProperties.S3 s3 = properties.getS3();
        if (s3.getEndpoint() == null || s3.getEndpoint().isBlank()) {
            throw new IllegalStateException(
                    "agentbench.artifacts.s3.endpoint is required when ARTIFACT_STORAGE=s3");
        }
        if (s3.getAccessKey() == null || s3.getAccessKey().isBlank()
                || s3.getSecretKey() == null || s3.getSecretKey().isBlank()) {
            throw new IllegalStateException(
                    "agentbench.artifacts.s3.access-key and secret-key are required when ARTIFACT_STORAGE=s3");
        }

        S3Configuration serviceConfig = S3Configuration.builder()
                .pathStyleAccessEnabled(s3.isPathStyle())
                .build();

        S3Client client = S3Client.builder()
                .endpointOverride(URI.create(s3.getEndpoint()))
                .region(Region.of(s3.getRegion() == null || s3.getRegion().isBlank() ? "us-east-1" : s3.getRegion()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(s3.getAccessKey(), s3.getSecretKey())))
                .serviceConfiguration(serviceConfig)
                .build();

        if (s3.getObjectLock().isEnabled()) {
            S3ObjectLockSupport.validateEnabledConfig(properties);
        }
        ensureBucket(client, s3.getBucket(), s3.isCreateBucket(), s3.getObjectLock());
        if (s3.getObjectLock().exportsAudit()) {
            String auditBucket = S3ObjectLockSupport.resolveAuditBucket(properties);
            if (!auditBucket.equals(s3.getBucket())) {
                ensureBucket(client, auditBucket, s3.isCreateBucket(), s3.getObjectLock());
            }
        }
        return client;
    }

    private static void ensureBucket(
            S3Client client,
            String bucket,
            boolean createBucket,
            S3ObjectLockProperties objectLock) {
        try {
            client.headBucket(HeadBucketRequest.builder().bucket(bucket).build());
            log.info("S3 artifact bucket ready: {}", bucket);
        } catch (NoSuchBucketException missing) {
            if (!createBucket) {
                throw new IllegalStateException("S3 bucket missing and create-bucket=false: " + bucket, missing);
            }
            createBucket(client, bucket, objectLock);
        } catch (S3Exception ex) {
            // MinIO may return 404 without NoSuchBucketException mapping in some versions.
            if (ex.statusCode() == 404 && createBucket) {
                createBucket(client, bucket, objectLock);
                return;
            }
            throw new IllegalStateException("Failed to access S3 artifact bucket: " + bucket, ex);
        }
    }

    private static void createBucket(S3Client client, String bucket, S3ObjectLockProperties objectLock) {
        try {
            CreateBucketRequest.Builder create = CreateBucketRequest.builder().bucket(bucket);
            // Object Lock can only be enabled at bucket creation (AWS + MinIO).
            if (objectLock != null && objectLock.isEnabled()) {
                create.objectLockEnabledForBucket(true);
            }
            client.createBucket(create.build());
            log.info("Created S3 bucket: {} objectLock={}", bucket,
                    objectLock != null && objectLock.isEnabled());
        } catch (S3Exception ex) {
            throw new IllegalStateException("Failed to create S3 bucket: " + bucket, ex);
        }
    }
}
