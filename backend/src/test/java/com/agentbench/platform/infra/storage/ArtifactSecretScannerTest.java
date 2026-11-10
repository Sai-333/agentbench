package com.agentbench.platform.infra.storage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ArtifactSecretScannerTest {

    private NamedParameterJdbcTemplate jdbc;
    private ArtifactSecretScanProperties properties;
    private ArtifactSecretScanner scanner;

    @BeforeEach
    void setUp() {
        jdbc = mock(NamedParameterJdbcTemplate.class);
        when(jdbc.update(anyString(), any(org.springframework.jdbc.core.namedparam.SqlParameterSource.class)))
                .thenReturn(1);
        properties = new ArtifactSecretScanProperties();
        properties.setEnabled(true);
        properties.setMode("REDACT");
        scanner = new ArtifactSecretScanner(properties, jdbc);
    }

    @Test
    void redactsAwsAccessKeyAndDoesNotKeepRawSecret() {
        String input = "export AWS_ACCESS_KEY_ID=AKIAIOSFODNN7EXAMPLE\n";
        ArtifactSecretScanner.ScanResult result = scanner.prepareText("orgs/x/patch.diff", input, "text/x-diff");
        assertTrue(result.modified());
        assertFalse(result.content().contains("AKIAIOSFODNN7EXAMPLE"));
        assertTrue(result.patternIds().contains("aws_access_key"));
        assertTrue(result.findingCount() >= 1);
    }

    @Test
    void redactsPrivateKeyBlock() {
        String input = """
                -----BEGIN RSA PRIVATE KEY-----
                MIIEpAIBAAKCAQEA0Z3VS5JJcds3xfn/ygWyF6PZGFw6examplekeymaterialhere
                -----END RSA PRIVATE KEY-----
                """;
        ArtifactSecretScanner.ScanResult result = scanner.prepareText("key.pem", input, "text/plain");
        assertTrue(result.modified());
        assertFalse(result.content().contains("BEGIN RSA PRIVATE KEY"));
        assertTrue(result.patternIds().contains("private_key"));
    }

    @Test
    void redactsJwt() {
        String jwt = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9."
                + "eyJzdWIiOiIxMjM0NTY3ODkwIiwibmFtZSI6IkpvaG4ifQ."
                + "SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c";
        String input = "Authorization token=" + jwt;
        ArtifactSecretScanner.ScanResult result = scanner.prepareText("log.txt", input, "text/plain");
        assertTrue(result.modified());
        assertFalse(result.content().contains(jwt));
        assertTrue(result.patternIds().contains("jwt"));
    }

    @Test
    void redactsEnvPasswordDump() {
        String input = "DATABASE_URL=postgres://x\npassword=hunter2-super-secret\n";
        ArtifactSecretScanner.ScanResult result = scanner.prepareText("env.dump", input, "text/plain");
        assertTrue(result.modified());
        assertFalse(result.content().contains("hunter2-super-secret"));
        assertTrue(result.patternIds().contains("env_password"));
    }

    @Test
    void redactsOpenaiSkKey() {
        String input = "api_key=sk-abcdefghijklmnopqrstuvwxyz012345";
        ArtifactSecretScanner.ScanResult result = scanner.prepareText("cfg", input, "text/plain");
        assertTrue(result.modified());
        assertFalse(result.content().contains("sk-abcdefghijklmnopqrstuvwxyz012345"));
    }

    @Test
    void quarantineModeRefusesPersistWithoutLeakingSecret() {
        properties.setMode("QUARANTINE");
        scanner = new ArtifactSecretScanner(properties, jdbc);
        String secret = "AKIAIOSFODNN7EXAMPLE";
        ArtifactSecretQuarantinedException ex = assertThrows(
                ArtifactSecretQuarantinedException.class,
                () -> scanner.prepareText("orgs/a/b", "key=" + secret, "text/plain"));
        assertFalse(ex.getMessage().contains(secret));
        assertTrue(ex.findingCount() >= 1);
    }

    @Test
    void disabledPassesThroughRawContent() {
        properties.setEnabled(false);
        scanner = new ArtifactSecretScanner(properties, jdbc);
        String input = "password=hunter2-super-secret";
        ArtifactSecretScanner.ScanResult result = scanner.prepareText("x", input, "text/plain");
        assertFalse(result.modified());
        assertEquals(input, result.content());
    }

    @Test
    void secondScanOnRedactedContentIsIdempotent() {
        String input = "password=hunter2-super-secret\nAKIAIOSFODNN7EXAMPLE\n";
        String once = scanner.prepareText("a", input, "text/plain").content();
        ArtifactSecretScanner.ScanResult twice = scanner.prepareText("a", once, "text/plain");
        assertFalse(twice.modified());
        assertEquals(once, twice.content());
    }

    @Test
    void additionalCustomPatternIsApplied() {
        properties.setAdditionalPatterns(List.of("(?i)acme-secret-[A-Z0-9]{8,}"));
        scanner = new ArtifactSecretScanner(properties, jdbc);
        String input = "token=acme-secret-ABCDEFGH1234";
        ArtifactSecretScanner.ScanResult result = scanner.prepareText("c", input, "text/plain");
        assertTrue(result.modified());
        assertFalse(result.content().contains("acme-secret-ABCDEFGH1234"));
        assertTrue(result.patternIds().contains("custom_0"));
    }

    @Test
    void scanningStorageNeverWritesRawSecret(@TempDir Path tempDir) throws Exception {
        LocalFilesystemArtifactStorage local = new LocalFilesystemArtifactStorage(tempDir.toString(), 1024 * 1024);
        ArtifactStorage storage = new ScanningArtifactStorage(local, scanner);
        String secret = "AKIAIOSFODNN7EXAMPLE";
        String body = "leak " + secret + " in patch\n";
        String uri = storage.store(
                "orgs/demo/patch.diff",
                new java.io.ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)),
                body.getBytes(StandardCharsets.UTF_8).length,
                "text/x-diff");
        byte[] stored = storage.open(uri).orElseThrow().readAllBytes();
        String storedText = new String(stored, StandardCharsets.UTF_8);
        assertFalse(storedText.contains(secret));
        assertTrue(Files.readString(Path.of(uri.substring("file://".length()))).contains("[REDACTED")
                || !Files.readString(Path.of(uri.substring("file://".length()))).contains(secret));
    }
}
