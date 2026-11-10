package com.agentbench.platform.infra.storage;

import com.agentbench.execution.config.ArtifactRetentionProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ArtifactRetentionServiceTest {

    @TempDir
    Path tempDir;

    @Mock
    NamedParameterJdbcTemplate jdbc;

    private LocalFilesystemArtifactStorage storage;
    private ArtifactStorageProperties storageProperties;
    private ArtifactRetentionProperties retentionProperties;
    private ArtifactRetentionService service;

    @BeforeEach
    void setUp() throws Exception {
        storage = new LocalFilesystemArtifactStorage(tempDir.toString(), 1024 * 1024);
        storageProperties = new ArtifactStorageProperties();
        storageProperties.setStorage("local");
        storageProperties.setRoot(tempDir.toString());
        retentionProperties = new ArtifactRetentionProperties();
        retentionProperties.setEnabled(true);
        retentionProperties.setDays(7);
        when(jdbc.update(anyString(), any(MapSqlParameterSource.class))).thenReturn(0);
        when(jdbc.query(anyString(), any(MapSqlParameterSource.class), any(RowMapper.class)))
                .thenReturn(List.of());
        service = new ArtifactRetentionService(storageProperties, retentionProperties, storage, jdbc);
    }

    @Test
    void sweepSkippedWhenDisabled() {
        retentionProperties.setEnabled(false);
        ArtifactRetentionService.CleanupStats stats = service.sweep();
        assertEquals("SKIPPED", stats.status());
        assertEquals(0, stats.deletedFiles());
        assertEquals(0, stats.deletedMetadata());
    }

    @Test
    void sweepSkippedWhenDaysNonPositive() {
        retentionProperties.setDays(0);
        ArtifactRetentionService.CleanupStats stats = service.sweep();
        assertEquals("SKIPPED", stats.status());
    }

    @Test
    void localSweepDeletesExpiredKeepsFresh() throws Exception {
        byte[] oldPayload = "old-blob".getBytes(StandardCharsets.UTF_8);
        byte[] freshPayload = "fresh-blob".getBytes(StandardCharsets.UTF_8);
        String oldUri = storage.store("orgs/a/old.txt", new ByteArrayInputStream(oldPayload), oldPayload.length, "text/plain");
        String freshUri = storage.store("orgs/a/fresh.txt", new ByteArrayInputStream(freshPayload), freshPayload.length, "text/plain");
        Path oldFile = Path.of(oldUri.substring("file://".length()));
        Files.setLastModifiedTime(oldFile, java.nio.file.attribute.FileTime.from(Instant.now().minus(10, ChronoUnit.DAYS)));

        ArtifactRetentionService.CleanupStats stats = service.sweep();

        assertEquals("SUCCEEDED", stats.status());
        assertTrue(stats.deletedFiles() >= 1);
        assertTrue(storage.open(oldUri).isEmpty());
        assertTrue(storage.open(freshUri).isPresent());
    }

    @Test
    void purgeExpiredMetadataDeletesBlobAndMarksRow() throws Exception {
        byte[] payload = "patch-body".getBytes(StandardCharsets.UTF_8);
        String uri = storage.store("orgs/a/patch.diff", new ByteArrayInputStream(payload), payload.length, "text/x-diff");
        UUID patchId = UUID.randomUUID();

        when(jdbc.query(contains("patch_artifacts"), any(MapSqlParameterSource.class), any(RowMapper.class)))
                .thenAnswer(invocation -> {
                    RowMapper<?> mapper = invocation.getArgument(2);
                    ResultSet rs = org.mockito.Mockito.mock(ResultSet.class);
                    when(rs.getObject("id", UUID.class)).thenReturn(patchId);
                    when(rs.getString("storage_uri")).thenReturn(uri);
                    when(rs.getInt("byte_size")).thenReturn(payload.length);
                    return List.of(mapper.mapRow(rs, 0));
                });
        when(jdbc.update(contains("patch_artifacts"), any(MapSqlParameterSource.class))).thenReturn(1);
        when(jdbc.update(contains("agent_invocations"), any(MapSqlParameterSource.class))).thenReturn(2);

        Instant cutoff = Instant.now().minus(1, ChronoUnit.DAYS);
        ArtifactRetentionService.MetadataSweepResult result = service.purgeExpiredMetadata(cutoff);

        assertEquals(1, result.deletedBlobs());
        assertEquals(payload.length, result.freedBytes());
        assertEquals(3, result.deletedMetadata()); // 1 patch + 2 invocation URIs
        assertTrue(storage.open(uri).isEmpty());

        ArgumentCaptor<MapSqlParameterSource> params = ArgumentCaptor.forClass(MapSqlParameterSource.class);
        verify(jdbc).update(contains("purged_at"), params.capture());
        assertEquals(patchId, params.getValue().getValue("id"));
    }

    @Test
    void purgeExpiredMetadataRetainsNonExpiredSelection() {
        // query returns empty → nothing deleted
        Instant cutoff = Instant.now().minus(1, ChronoUnit.DAYS);
        ArtifactRetentionService.MetadataSweepResult result = service.purgeExpiredMetadata(cutoff);
        assertEquals(0, result.deletedBlobs());
        assertEquals(0, result.deletedMetadata());
        verify(jdbc, never()).update(contains("purged_at"), any(MapSqlParameterSource.class));
    }

    @Test
    void s3ModeAlsoSweepsLocalWorkspaces() throws Exception {
        ArtifactStorage mockS3 = org.mockito.Mockito.mock(ArtifactStorage.class);
        when(mockS3.sweepOlderThan(any())).thenReturn(ArtifactStorage.SweepResult.empty());
        storageProperties.setStorage("s3");
        service = new ArtifactRetentionService(storageProperties, retentionProperties, mockS3, jdbc);

        Path workspaceFile = tempDir.resolve("workspaces").resolve("eval-1").resolve("out.log");
        Files.createDirectories(workspaceFile.getParent());
        Files.writeString(workspaceFile, "stale");
        Files.setLastModifiedTime(workspaceFile,
                java.nio.file.attribute.FileTime.from(Instant.now().minus(30, ChronoUnit.DAYS)));

        ArtifactRetentionService.CleanupStats stats = service.sweep();
        assertEquals("SUCCEEDED", stats.status());
        assertTrue(stats.deletedFiles() >= 1);
        assertTrue(Files.notExists(workspaceFile));
        verify(mockS3).sweepOlderThan(any());
    }
}
