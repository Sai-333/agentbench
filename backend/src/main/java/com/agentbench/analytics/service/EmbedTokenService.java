package com.agentbench.analytics.service;

import com.agentbench.analytics.config.PublicLeaderboardProperties;
import com.agentbench.platform.error.ApiException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.Locale;
import java.util.Objects;

/**
 * HMAC-SHA256 signed embed tokens for published public leaderboards.
 * Format: {@code v1.<base64url(payloadJson)>.<base64url(sig)>}.
 */
@Service
public class EmbedTokenService {

    private static final String VERSION = "v1";
    private static final String HMAC_ALG = "HmacSHA256";

    private final byte[] secret;
    private final long defaultTtlSeconds;
    private final long maxTtlSeconds;
    private final Clock clock;
    private final ObjectMapper objectMapper;
    private final Base64.Encoder urlEncoder = Base64.getUrlEncoder().withoutPadding();
    private final Base64.Decoder urlDecoder = Base64.getUrlDecoder();

    public EmbedTokenService(
            PublicLeaderboardProperties properties,
            @Value("${agentbench.jwt.secret}") String jwtSecret,
            Clock clock,
            ObjectMapper objectMapper) {
        String configured = properties.getEmbedSecret();
        String resolved = (configured == null || configured.isBlank()) ? jwtSecret : configured.trim();
        byte[] bytes = resolved.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 32) {
            throw new IllegalStateException(
                    "agentbench.public-leaderboards.embed-secret (or jwt.secret fallback) must be ≥ 32 characters");
        }
        this.secret = bytes;
        this.defaultTtlSeconds = Math.max(60L, properties.getDefaultEmbedTtlSeconds());
        this.maxTtlSeconds = Math.max(this.defaultTtlSeconds, properties.getMaxEmbedTtlSeconds());
        this.clock = Objects.requireNonNull(clock, "clock");
        this.objectMapper = objectMapper;
    }

    public MintedToken mint(String publicSlug, Long ttlSeconds) {
        String slug = normalizeSlug(publicSlug);
        long ttl = ttlSeconds == null ? defaultTtlSeconds : ttlSeconds;
        if (ttl < 60L) {
            throw ApiException.validation("ttlSeconds must be at least 60");
        }
        if (ttl > maxTtlSeconds) {
            throw ApiException.validation("ttlSeconds must be at most " + maxTtlSeconds);
        }
        Instant now = clock.instant();
        Instant exp = now.plusSeconds(ttl);
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("slug", slug);
        payload.put("iat", now.getEpochSecond());
        payload.put("exp", exp.getEpochSecond());
        String payloadB64 = urlEncoder.encodeToString(toJson(payload));
        String signingInput = VERSION + "." + payloadB64;
        String sigB64 = urlEncoder.encodeToString(hmac(signingInput));
        return new MintedToken(signingInput + "." + sigB64, slug, now, exp, ttl);
    }

    /**
     * Validates signature + expiry and returns the bound slug.
     * Rejects tampered, malformed, or expired tokens with 401.
     */
    public String validate(String token) {
        if (token == null || token.isBlank()) {
            throw ApiException.unauthorized("Embed token required");
        }
        String trimmed = token.trim();
        String[] parts = trimmed.split("\\.");
        if (parts.length != 3 || !VERSION.equals(parts[0])) {
            throw ApiException.unauthorized("Invalid embed token");
        }
        String signingInput = parts[0] + "." + parts[1];
        byte[] expected;
        byte[] actual;
        try {
            expected = hmac(signingInput);
            actual = urlDecoder.decode(parts[2]);
        } catch (IllegalArgumentException ex) {
            throw ApiException.unauthorized("Invalid embed token");
        }
        if (!constantTimeEquals(expected, actual)) {
            throw ApiException.unauthorized("Invalid embed token");
        }
        JsonNode payload;
        try {
            payload = objectMapper.readTree(urlDecoder.decode(parts[1]));
        } catch (Exception ex) {
            throw ApiException.unauthorized("Invalid embed token");
        }
        JsonNode slugNode = payload.get("slug");
        JsonNode expNode = payload.get("exp");
        if (slugNode == null || !slugNode.isTextual() || slugNode.asText().isBlank()
                || expNode == null || !expNode.canConvertToLong()) {
            throw ApiException.unauthorized("Invalid embed token");
        }
        long expEpoch = expNode.asLong();
        if (clock.instant().getEpochSecond() >= expEpoch) {
            throw ApiException.unauthorized("Embed token expired");
        }
        return normalizeSlug(slugNode.asText());
    }

    public long defaultTtlSeconds() {
        return defaultTtlSeconds;
    }

    public long maxTtlSeconds() {
        return maxTtlSeconds;
    }

    private byte[] hmac(String signingInput) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALG);
            mac.init(new SecretKeySpec(secret, HMAC_ALG));
            return mac.doFinal(signingInput.getBytes(StandardCharsets.UTF_8));
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to sign embed token", ex);
        }
    }

    private byte[] toJson(ObjectNode payload) {
        try {
            return objectMapper.writeValueAsBytes(payload);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Unable to serialize embed token payload", ex);
        }
    }

    private static String normalizeSlug(String raw) {
        if (raw == null || raw.isBlank()) {
            throw ApiException.validation("publicSlug is required");
        }
        return raw.trim().toLowerCase(Locale.ROOT);
    }

    private static boolean constantTimeEquals(byte[] a, byte[] b) {
        if (a == null || b == null || a.length != b.length) {
            return false;
        }
        int result = 0;
        for (int i = 0; i < a.length; i++) {
            result |= a[i] ^ b[i];
        }
        return result == 0;
    }

    public record MintedToken(
            String token,
            String publicSlug,
            Instant issuedAt,
            Instant expiresAt,
            long ttlSeconds
    ) {
    }
}
