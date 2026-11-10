package com.agentbench.platform.infra.storage;

/**
 * Opt-in AWS S3 Object Lock settings for artifact puts and/or WORM audit export.
 *
 * <p>Default off. Requires {@code ARTIFACT_STORAGE=s3} and a bucket created with Object Lock
 * (versioning) enabled. Does <strong>not</strong> replace Postgres append-only WORM
 * ({@code docs/AUDIT_WORM.md}).
 */
public class S3ObjectLockProperties {

    /** Master switch. When false, no Object Lock headers or audit export run. */
    private boolean enabled = false;

    /** Retention mode applied on put / audit export. */
    private String mode = "COMPLIANCE";

    /** Retain-until = now + retentionDays. Must be &gt; 0 when enabled. */
    private int retentionDays = 2555;

    /** Apply Object Lock on artifact {@code store()} puts. */
    private boolean applyToArtifacts = false;

    /** Export {@code platform.worm_audit_events} to S3 with Object Lock. */
    private boolean applyToAuditExport = true;

    /**
     * When true (default), startup fails unless {@code GetObjectLockConfiguration} reports
     * Object Lock enabled on the target bucket(s). Set false only for local experiments
     * where the endpoint cannot answer (not a Compliance claim).
     */
    private boolean requireBucketObjectLock = true;

    /**
     * Optional dedicated audit-export bucket. Blank → use the artifact bucket.
     * Create with Object Lock enabled before turning this on.
     */
    private String auditBucket = "";

    /** Key prefix for exported audit JSON objects (no leading slash). */
    private String auditKeyPrefix = "audit/worm/";

    /** Scheduled audit export poll interval. */
    private long auditExportIntervalMs = 60_000L;

    /** Max worm rows exported per poll. */
    private int auditExportBatchSize = 200;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }

    public int getRetentionDays() {
        return retentionDays;
    }

    public void setRetentionDays(int retentionDays) {
        this.retentionDays = retentionDays;
    }

    public boolean isApplyToArtifacts() {
        return applyToArtifacts;
    }

    public void setApplyToArtifacts(boolean applyToArtifacts) {
        this.applyToArtifacts = applyToArtifacts;
    }

    public boolean isApplyToAuditExport() {
        return applyToAuditExport;
    }

    public void setApplyToAuditExport(boolean applyToAuditExport) {
        this.applyToAuditExport = applyToAuditExport;
    }

    public boolean isRequireBucketObjectLock() {
        return requireBucketObjectLock;
    }

    public void setRequireBucketObjectLock(boolean requireBucketObjectLock) {
        this.requireBucketObjectLock = requireBucketObjectLock;
    }

    public String getAuditBucket() {
        return auditBucket;
    }

    public void setAuditBucket(String auditBucket) {
        this.auditBucket = auditBucket;
    }

    public String getAuditKeyPrefix() {
        return auditKeyPrefix;
    }

    public void setAuditKeyPrefix(String auditKeyPrefix) {
        this.auditKeyPrefix = auditKeyPrefix;
    }

    public long getAuditExportIntervalMs() {
        return auditExportIntervalMs;
    }

    public void setAuditExportIntervalMs(long auditExportIntervalMs) {
        this.auditExportIntervalMs = auditExportIntervalMs;
    }

    public int getAuditExportBatchSize() {
        return auditExportBatchSize;
    }

    public void setAuditExportBatchSize(int auditExportBatchSize) {
        this.auditExportBatchSize = auditExportBatchSize;
    }

    public boolean protectsArtifacts() {
        return enabled && applyToArtifacts;
    }

    public boolean exportsAudit() {
        return enabled && applyToAuditExport;
    }

    public S3ObjectLockMode resolvedMode() {
        return S3ObjectLockMode.parse(mode);
    }
}
