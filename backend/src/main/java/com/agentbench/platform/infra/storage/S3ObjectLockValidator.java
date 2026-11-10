package com.agentbench.platform.infra.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectLockConfigurationRequest;
import software.amazon.awssdk.services.s3.model.GetObjectLockConfigurationResponse;
import software.amazon.awssdk.services.s3.model.ObjectLockEnabled;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Fail-fast validation when Object Lock is enabled: config sanity + optional bucket lock probe.
 */
@Component
@Order(100)
@ConditionalOnProperty(name = "agentbench.artifacts.storage", havingValue = "s3")
public class S3ObjectLockValidator implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(S3ObjectLockValidator.class);

    private final S3Client s3Client;
    private final ArtifactStorageProperties properties;

    public S3ObjectLockValidator(S3Client artifactS3Client, ArtifactStorageProperties properties) {
        this.s3Client = artifactS3Client;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        validate();
    }

    /** Package-visible for unit tests. */
    void validate() {
        S3ObjectLockProperties lock = properties.getS3().getObjectLock();
        if (!lock.isEnabled()) {
            log.info("S3 Object Lock disabled (Postgres WORM remains the default audit path)");
            return;
        }
        S3ObjectLockSupport.validateEnabledConfig(properties);
        log.info("S3 Object Lock enabled mode={} retentionDays={} artifacts={} auditExport={} requireBucketLock={}",
                lock.getMode(),
                lock.getRetentionDays(),
                lock.isApplyToArtifacts(),
                lock.isApplyToAuditExport(),
                lock.isRequireBucketObjectLock());

        if (!lock.isRequireBucketObjectLock()) {
            log.warn("Object Lock require-bucket-object-lock=false — skipping GetObjectLockConfiguration; "
                    + "do not claim Compliance-mode WORM without a verified Object Lock bucket");
            return;
        }

        Set<String> buckets = new LinkedHashSet<>();
        if (lock.protectsArtifacts()) {
            buckets.add(properties.getS3().getBucket());
        }
        if (lock.exportsAudit()) {
            buckets.add(S3ObjectLockSupport.resolveAuditBucket(properties));
        }
        for (String bucket : buckets) {
            assertObjectLockEnabled(bucket);
        }
    }

    private void assertObjectLockEnabled(String bucket) {
        try {
            GetObjectLockConfigurationResponse response = s3Client.getObjectLockConfiguration(
                    GetObjectLockConfigurationRequest.builder().bucket(bucket).build());
            ObjectLockEnabled enabled = response.objectLockConfiguration() == null
                    ? null
                    : response.objectLockConfiguration().objectLockEnabled();
            if (enabled != ObjectLockEnabled.ENABLED) {
                throw new IllegalStateException(
                        "S3 bucket does not have Object Lock enabled: " + bucket
                                + " (create with ObjectLockEnabledForBucket / mc mb --with-lock)");
            }
            log.info("Verified Object Lock enabled on bucket={}", bucket);
        } catch (IllegalStateException ex) {
            throw ex;
        } catch (S3Exception ex) {
            throw new IllegalStateException(
                    "Failed GetObjectLockConfiguration for bucket=" + bucket
                            + " status=" + ex.statusCode()
                            + " — Object Lock must be enabled at bucket creation on AWS/MinIO",
                    ex);
        } catch (RuntimeException ex) {
            throw new IllegalStateException(
                    "Failed verifying Object Lock on bucket=" + bucket + ": " + ex.getMessage(), ex);
        }
    }
}
