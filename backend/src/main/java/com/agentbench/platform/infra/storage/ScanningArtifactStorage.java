package com.agentbench.platform.infra.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.Optional;

/**
 * {@link ArtifactStorage} decorator that runs {@link ArtifactSecretScanner} before every
 * {@link #store}. Local and S3 backends remain unchanged; TTL/GC still uses the delegate.
 */
public class ScanningArtifactStorage implements ArtifactStorage {

    private static final Logger log = LoggerFactory.getLogger(ScanningArtifactStorage.class);

    private final ArtifactStorage delegate;
    private final ArtifactSecretScanner scanner;

    public ScanningArtifactStorage(ArtifactStorage delegate, ArtifactSecretScanner scanner) {
        this.delegate = delegate;
        this.scanner = scanner;
    }

    ArtifactStorage delegate() {
        return delegate;
    }

    @Override
    public String store(String key, InputStream content, long contentLength, String contentType) {
        byte[] bytes;
        try {
            bytes = content.readAllBytes();
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to buffer artifact for secret scan key=" + key, ex);
        }
        if (contentLength >= 0 && bytes.length != contentLength) {
            log.debug("Artifact contentLength={} but read {} bytes for key={}", contentLength, bytes.length, key);
        }
        byte[] safe = scanner.prepareBytes(key, bytes, contentType);
        return delegate.store(key, new ByteArrayInputStream(safe), safe.length, contentType);
    }

    @Override
    public Optional<InputStream> open(String storageUri) {
        return delegate.open(storageUri);
    }

    @Override
    public void delete(String storageUri) {
        delegate.delete(storageUri);
    }

    @Override
    public SweepResult sweepOlderThan(Instant cutoff) {
        return delegate.sweepOlderThan(cutoff);
    }
}
