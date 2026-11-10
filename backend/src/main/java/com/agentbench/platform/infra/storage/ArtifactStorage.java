package com.agentbench.platform.infra.storage;

import java.io.InputStream;
import java.time.Instant;
import java.util.Optional;

/**
 * Provider-independent artifact storage port.
 *
 * <p>Implementations: local filesystem (default for demos) and S3-compatible object
 * stores (MinIO / AWS S3). Callers stay implementation-agnostic via storage URIs.
 */
public interface ArtifactStorage {

    /**
     * Stores bytes at the given relative key and returns a stable storage URI.
     *
     * @throws IllegalArgumentException if the key is invalid or contentLength exceeds the configured max
     */
    String store(String key, InputStream content, long contentLength, String contentType);

    /**
     * Opens a stored artifact for reading, if present.
     */
    Optional<InputStream> open(String storageUri);

    /**
     * Deletes an artifact if present. Idempotent.
     */
    void delete(String storageUri);

    /**
     * Deletes objects older than {@code cutoff}. Used by retention sweeps.
     * Implementations that cannot age objects return a zero result.
     */
    SweepResult sweepOlderThan(Instant cutoff);

    /**
     * Result of a retention sweep against this store.
     */
    record SweepResult(int deletedObjects, long freedBytes) {
        public static SweepResult empty() {
            return new SweepResult(0, 0L);
        }
    }
}
