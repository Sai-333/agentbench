package com.agentbench.platform.infra.storage;

import java.io.InputStream;
import java.util.Optional;

/**
 * Provider-independent artifact storage port.
 *
 * <p>Local filesystem implementation is used first (Docker volume). An S3-compatible
 * implementation will be added later without changing callers.
 */
public interface ArtifactStorage {

    /**
     * Stores bytes at the given relative key and returns a stable storage URI.
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
}
