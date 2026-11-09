package com.agentbench.identity.domain;

import java.time.Instant;
import java.util.UUID;

public record SecretRef(
        UUID id,
        UUID orgId,
        String name,
        byte[] ciphertext,
        int keyVersion,
        String purpose,
        Instant createdAt,
        Instant rotatedAt
) {
}
