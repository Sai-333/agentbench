package com.agentbench.platform.infra.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Artifact blob-store selection and S3-compatible settings.
 *
 * <p>{@code storage=local} (default) uses a Docker volume / filesystem root.
 * {@code storage=s3} uses an S3-compatible endpoint (MinIO, AWS S3, etc.).
 */
@ConfigurationProperties(prefix = "agentbench.artifacts")
public class ArtifactStorageProperties {

    /**
     * Active backend: {@code local} or {@code s3}.
     * Bound from {@code ARTIFACT_STORAGE} / {@code AGENTBENCH_ARTIFACT_STORAGE}.
     */
    private String storage = "local";

    /** Local filesystem root (also used for evaluation workspaces). */
    private String root = "/var/agentbench/artifacts";

    /**
     * Hard ceiling for a single stored object (bytes). Rejects store() when contentLength exceeds this.
     * Aligns with the designed max log artifact size (20 MiB default).
     */
    private long maxObjectBytes = 20_971_520L;

    private final S3 s3 = new S3();

    public String getStorage() {
        return storage;
    }

    public void setStorage(String storage) {
        this.storage = storage;
    }

    public String getRoot() {
        return root;
    }

    public void setRoot(String root) {
        this.root = root;
    }

    public long getMaxObjectBytes() {
        return maxObjectBytes;
    }

    public void setMaxObjectBytes(long maxObjectBytes) {
        this.maxObjectBytes = maxObjectBytes;
    }

    public S3 getS3() {
        return s3;
    }

    public boolean isS3() {
        return "s3".equalsIgnoreCase(storage);
    }

    public boolean isLocal() {
        return !isS3();
    }

    public static class S3 {
        private String endpoint = "";
        private String bucket = "agentbench-artifacts";
        private String region = "us-east-1";
        private String accessKey = "";
        private String secretKey = "";
        /** Path-style addressing (required for MinIO). */
        private boolean pathStyle = true;
        /** Optional key prefix inside the bucket (no leading slash). */
        private String keyPrefix = "";
        /** Create the bucket on startup when missing (local MinIO convenience). */
        private boolean createBucket = true;

        /**
         * Opt-in S3 Object Lock (governance/compliance retention). Default off.
         * See {@link S3ObjectLockProperties} and {@code docs/AUDIT_WORM.md}.
         */
        private final S3ObjectLockProperties objectLock = new S3ObjectLockProperties();

        public String getEndpoint() {
            return endpoint;
        }

        public void setEndpoint(String endpoint) {
            this.endpoint = endpoint;
        }

        public String getBucket() {
            return bucket;
        }

        public void setBucket(String bucket) {
            this.bucket = bucket;
        }

        public String getRegion() {
            return region;
        }

        public void setRegion(String region) {
            this.region = region;
        }

        public String getAccessKey() {
            return accessKey;
        }

        public void setAccessKey(String accessKey) {
            this.accessKey = accessKey;
        }

        public String getSecretKey() {
            return secretKey;
        }

        public void setSecretKey(String secretKey) {
            this.secretKey = secretKey;
        }

        public boolean isPathStyle() {
            return pathStyle;
        }

        public void setPathStyle(boolean pathStyle) {
            this.pathStyle = pathStyle;
        }

        public String getKeyPrefix() {
            return keyPrefix;
        }

        public void setKeyPrefix(String keyPrefix) {
            this.keyPrefix = keyPrefix;
        }

        public boolean isCreateBucket() {
            return createBucket;
        }

        public void setCreateBucket(boolean createBucket) {
            this.createBucket = createBucket;
        }

        public S3ObjectLockProperties getObjectLock() {
            return objectLock;
        }
    }
}
