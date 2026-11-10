package com.agentbench.platform.infra.storage;

/**
 * S3 Object Lock retention mode. Maps to AWS {@code x-amz-object-lock-mode}.
 *
 * <p>{@link #COMPLIANCE} cannot be shortened or bypassed by any principal (including root)
 * until the retain-until date. {@link #GOVERNANCE} can be bypassed with
 * {@code s3:BypassGovernanceRetention}. Prefer COMPLIANCE for off-box audit evidence.
 */
public enum S3ObjectLockMode {
    GOVERNANCE,
    COMPLIANCE;

    public static S3ObjectLockMode parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("S3 Object Lock mode must be GOVERNANCE or COMPLIANCE");
        }
        try {
            return S3ObjectLockMode.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException(
                    "S3 Object Lock mode must be GOVERNANCE or COMPLIANCE, got: " + raw, ex);
        }
    }
}
