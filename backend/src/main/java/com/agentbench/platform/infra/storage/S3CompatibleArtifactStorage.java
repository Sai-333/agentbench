package com.agentbench.platform.infra.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.S3Object;

import java.io.InputStream;
import java.time.Instant;
import java.util.Optional;

/**
 * S3-compatible {@link ArtifactStorage} (AWS S3, MinIO, etc.).
 * Active when {@code agentbench.artifacts.storage=s3}.
 *
 * <p>Storage URIs use the form {@code s3://{bucket}/{key}}.
 *
 * <p>When {@code agentbench.artifacts.s3.object-lock} protects artifacts, puts include
 * Object Lock retention, existing keys are refused (no overwrite), and delete/sweep are
 * refused while retention applies at the application layer.
 */
@Component
@ConditionalOnProperty(name = "agentbench.artifacts.storage", havingValue = "s3")
public class S3CompatibleArtifactStorage implements ArtifactStorage {

    private static final Logger log = LoggerFactory.getLogger(S3CompatibleArtifactStorage.class);
    private static final String URI_PREFIX = "s3://";

    private final S3Client s3Client;
    private final String bucket;
    private final String keyPrefix;
    private final long maxObjectBytes;
    private final S3ObjectLockProperties objectLock;

    public S3CompatibleArtifactStorage(S3Client artifactS3Client, ArtifactStorageProperties properties) {
        this.s3Client = artifactS3Client;
        this.bucket = properties.getS3().getBucket();
        String prefix = properties.getS3().getKeyPrefix();
        if (prefix == null || prefix.isBlank()) {
            this.keyPrefix = "";
        } else {
            String normalized = prefix.replaceAll("^/+", "").replaceAll("/+$", "");
            this.keyPrefix = normalized.isEmpty() ? "" : normalized + "/";
        }
        this.maxObjectBytes = properties.getMaxObjectBytes();
        this.objectLock = properties.getS3().getObjectLock();
        log.info("S3-compatible artifact storage ready bucket={} prefix={} pathStyle={} maxObjectBytes={} objectLockArtifacts={}",
                bucket, keyPrefix, properties.getS3().isPathStyle(), maxObjectBytes, objectLock.protectsArtifacts());
    }

    @Override
    public String store(String key, InputStream content, long contentLength, String contentType) {
        enforceSize(contentLength);
        String objectKey = toObjectKey(key);
        if (objectLock.protectsArtifacts()) {
            rejectIfExists(objectKey);
        }
        PutObjectRequest.Builder put = PutObjectRequest.builder()
                .bucket(bucket)
                .key(objectKey);
        if (contentType != null && !contentType.isBlank()) {
            put.contentType(contentType);
        }
        if (contentLength >= 0) {
            put.contentLength(contentLength);
        }
        if (objectLock.protectsArtifacts()) {
            S3ObjectLockSupport.applyRetention(put, objectLock, Instant.now());
        }
        try {
            s3Client.putObject(put.build(), RequestBody.fromInputStream(content, contentLength));
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to store S3 artifact key=" + objectKey, ex);
        }
        return toUri(objectKey);
    }

    @Override
    public Optional<InputStream> open(String storageUri) {
        ObjectRef ref = parseUri(storageUri);
        try {
            return Optional.of(s3Client.getObject(GetObjectRequest.builder()
                    .bucket(ref.bucket())
                    .key(ref.key())
                    .build()));
        } catch (NoSuchKeyException missing) {
            return Optional.empty();
        } catch (S3Exception ex) {
            if (ex.statusCode() == 404) {
                return Optional.empty();
            }
            throw new IllegalStateException("Failed to open S3 artifact uri=" + storageUri, ex);
        }
    }

    @Override
    public void delete(String storageUri) {
        if (objectLock.protectsArtifacts()) {
            throw new ObjectLockProtectedException(
                    "Refusing delete of S3 artifact under Object Lock retention: " + storageUri
                            + " (mode=" + objectLock.getMode()
                            + ", retentionDays=" + objectLock.getRetentionDays() + ")");
        }
        ObjectRef ref = parseUri(storageUri);
        try {
            s3Client.deleteObject(DeleteObjectRequest.builder()
                    .bucket(ref.bucket())
                    .key(ref.key())
                    .build());
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to delete S3 artifact uri=" + storageUri, ex);
        }
    }

    @Override
    public SweepResult sweepOlderThan(Instant cutoff) {
        if (objectLock.protectsArtifacts()) {
            log.info("Skipping S3 orphan sweep while Object Lock protects artifacts (cutoff={})", cutoff);
            return SweepResult.empty();
        }
        int deleted = 0;
        long freed = 0L;
        String continuation = null;
        do {
            ListObjectsV2Request.Builder list = ListObjectsV2Request.builder()
                    .bucket(bucket)
                    .maxKeys(1000);
            if (!keyPrefix.isEmpty()) {
                list.prefix(keyPrefix);
            }
            if (continuation != null) {
                list.continuationToken(continuation);
            }
            var page = s3Client.listObjectsV2(list.build());
            for (S3Object object : page.contents()) {
                Instant modified = object.lastModified();
                if (modified != null && modified.isBefore(cutoff)) {
                    try {
                        s3Client.deleteObject(DeleteObjectRequest.builder()
                                .bucket(bucket)
                                .key(object.key())
                                .build());
                        deleted++;
                        freed += object.size() == null ? 0L : object.size();
                    } catch (Exception ex) {
                        log.warn("Failed deleting expired S3 object {}: {}", object.key(), ex.getMessage());
                    }
                }
            }
            continuation = Boolean.TRUE.equals(page.isTruncated()) ? page.nextContinuationToken() : null;
        } while (continuation != null);
        return new SweepResult(deleted, freed);
    }

    private void rejectIfExists(String objectKey) {
        try {
            s3Client.headObject(HeadObjectRequest.builder()
                    .bucket(bucket)
                    .key(objectKey)
                    .build());
            throw new ObjectLockProtectedException(
                    "Refusing overwrite of existing S3 object under Object Lock: s3://" + bucket + "/" + objectKey);
        } catch (ObjectLockProtectedException protectedEx) {
            throw protectedEx;
        } catch (NoSuchKeyException missing) {
            // ok — key free
        } catch (S3Exception ex) {
            if (ex.statusCode() == 404) {
                return;
            }
            throw new IllegalStateException("Failed HeadObject before Object Lock put key=" + objectKey, ex);
        }
    }

    private void enforceSize(long contentLength) {
        if (maxObjectBytes > 0 && contentLength > maxObjectBytes) {
            throw new IllegalArgumentException(
                    "Artifact exceeds max-object-bytes=" + maxObjectBytes + " contentLength=" + contentLength);
        }
    }

    private String toObjectKey(String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("Artifact key must not be blank");
        }
        String normalized = key.replace('\\', '/').replaceAll("^/+", "");
        if (normalized.contains("..")) {
            throw new IllegalArgumentException("Artifact key must not contain '..': " + key);
        }
        return keyPrefix + normalized;
    }

    private String toUri(String objectKey) {
        return URI_PREFIX + bucket + "/" + objectKey;
    }

    private ObjectRef parseUri(String storageUri) {
        if (storageUri == null || !storageUri.startsWith(URI_PREFIX)) {
            throw new IllegalArgumentException("Unsupported storage URI: " + storageUri);
        }
        String remainder = storageUri.substring(URI_PREFIX.length());
        int slash = remainder.indexOf('/');
        if (slash <= 0 || slash == remainder.length() - 1) {
            throw new IllegalArgumentException("Malformed S3 storage URI: " + storageUri);
        }
        String uriBucket = remainder.substring(0, slash);
        String key = remainder.substring(slash + 1);
        if (!bucket.equals(uriBucket)) {
            throw new IllegalArgumentException("S3 URI bucket mismatch: expected=" + bucket + " got=" + uriBucket);
        }
        if (!keyPrefix.isEmpty() && !key.startsWith(keyPrefix)) {
            throw new IllegalArgumentException("S3 URI key outside configured prefix: " + storageUri);
        }
        return new ObjectRef(uriBucket, key);
    }

    private record ObjectRef(String bucket, String key) {
    }
}
