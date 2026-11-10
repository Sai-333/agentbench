package com.agentbench.platform.infra.storage;

/**
 * Thrown when artifact secret-scan mode is {@code QUARANTINE} and findings were detected.
 * Message must never include the secret value.
 */
public class ArtifactSecretQuarantinedException extends IllegalArgumentException {

    private final int findingCount;
    private final String patternIds;

    public ArtifactSecretQuarantinedException(String storageKey, int findingCount, String patternIds) {
        super("Artifact secret scan quarantined key=" + storageKey
                + " findings=" + findingCount
                + " patterns=" + patternIds);
        this.findingCount = findingCount;
        this.patternIds = patternIds == null ? "" : patternIds;
    }

    public int findingCount() {
        return findingCount;
    }

    public String patternIds() {
        return patternIds;
    }
}
