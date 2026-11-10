package com.agentbench.analytics.service;

import com.agentbench.analytics.config.PublicLeaderboardProperties;
import com.agentbench.platform.error.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Deterministic mint/validate tests — fixed Clock, no wall-clock flakiness.
 */
class EmbedTokenServiceTest {

    private static final String SECRET = "test-only-embed-secret-key-32chars!!";
    private static final Instant T0 = Instant.parse("2026-01-15T12:00:00Z");

    private PublicLeaderboardProperties properties;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        properties = new PublicLeaderboardProperties();
        properties.setEmbedSecret(SECRET);
        properties.setDefaultEmbedTtlSeconds(3_600L);
        properties.setMaxEmbedTtlSeconds(86_400L);
        objectMapper = new ObjectMapper();
    }

    @Test
    void mintAndValidateRoundTrip() {
        EmbedTokenService service = serviceAt(T0);
        EmbedTokenService.MintedToken minted = service.mint("demo-public-board", 3_600L);
        assertEquals("demo-public-board", minted.publicSlug());
        assertEquals(T0, minted.issuedAt());
        assertEquals(T0.plusSeconds(3_600L), minted.expiresAt());
        assertTrue(minted.token().startsWith("v1."));

        EmbedTokenService validator = serviceAt(T0.plusSeconds(60));
        assertEquals("demo-public-board", validator.validate(minted.token()));
    }

    @Test
    void rejectsExpiredTokenWithoutSleeping() {
        EmbedTokenService mintService = serviceAt(T0);
        EmbedTokenService.MintedToken minted = mintService.mint("demo-public-board", 120L);

        EmbedTokenService afterExpiry = serviceAt(T0.plusSeconds(121));
        ApiException ex = assertThrows(ApiException.class, () -> afterExpiry.validate(minted.token()));
        assertEquals(401, ex.getStatus().value());
        assertTrue(ex.getMessage().toLowerCase().contains("expired"));
    }

    @Test
    void rejectsTamperedPayload() {
        EmbedTokenService service = serviceAt(T0);
        String token = service.mint("demo-public-board", 3_600L).token();
        String[] parts = token.split("\\.");
        String payloadJson = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
        String tamperedJson = payloadJson.replace("demo-public-board", "other-board");
        String tampered = parts[0] + "."
                + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(tamperedJson.getBytes(StandardCharsets.UTF_8))
                + "." + parts[2];

        ApiException ex = assertThrows(ApiException.class, () -> service.validate(tampered));
        assertEquals(401, ex.getStatus().value());
    }

    @Test
    void rejectsTamperedSignature() {
        EmbedTokenService service = serviceAt(T0);
        String token = service.mint("demo-public-board", 3_600L).token();
        String[] parts = token.split("\\.");
        String badSig = parts[2].charAt(0) == 'A' ? "B" + parts[2].substring(1) : "A" + parts[2].substring(1);
        String tampered = parts[0] + "." + parts[1] + "." + badSig;

        ApiException ex = assertThrows(ApiException.class, () -> service.validate(tampered));
        assertEquals(401, ex.getStatus().value());
    }

    @Test
    void rejectsMalformedToken() {
        EmbedTokenService service = serviceAt(T0);
        assertThrows(ApiException.class, () -> service.validate("not-a-token"));
        assertThrows(ApiException.class, () -> service.validate(""));
        assertThrows(ApiException.class, () -> service.validate(null));
    }

    @Test
    void rejectsTtlAboveMax() {
        EmbedTokenService service = serviceAt(T0);
        ApiException ex = assertThrows(ApiException.class,
                () -> service.mint("demo-public-board", 86_401L));
        assertEquals(400, ex.getStatus().value());
    }

    private EmbedTokenService serviceAt(Instant instant) {
        return new EmbedTokenService(
                properties,
                "unused-jwt-secret-because-embed-secret-set!!!!",
                Clock.fixed(instant, ZoneOffset.UTC),
                objectMapper
        );
    }
}
