package com.agentbench.platform.infra.storage;

/**
 * Raised when an overwrite or delete is refused because S3 Object Lock retention applies.
 */
public class ObjectLockProtectedException extends IllegalStateException {

    public ObjectLockProtectedException(String message) {
        super(message);
    }

    public ObjectLockProtectedException(String message, Throwable cause) {
        super(message, cause);
    }
}
