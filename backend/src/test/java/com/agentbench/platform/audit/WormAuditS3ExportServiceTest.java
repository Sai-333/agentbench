package com.agentbench.platform.audit;

import com.agentbench.platform.infra.storage.ArtifactStorageProperties;
import com.agentbench.platform.infra.storage.S3ObjectLockProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ObjectLockMode;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WormAuditS3ExportServiceTest {

    @Mock
    private S3Client s3Client;

    @Mock
    private NamedParameterJdbcTemplate jdbc;

    private ArtifactStorageProperties properties;
    private WormAuditS3ExportService exporter;

    @BeforeEach
    void setUp() {
        properties = new ArtifactStorageProperties();
        properties.setStorage("s3");
        properties.getS3().setBucket("agentbench-artifacts");
        S3ObjectLockProperties lock = properties.getS3().getObjectLock();
        lock.setEnabled(true);
        lock.setMode("COMPLIANCE");
        lock.setRetentionDays(365);
        lock.setApplyToArtifacts(false);
        lock.setApplyToAuditExport(true);
        lock.setAuditKeyPrefix("audit/worm/");
        lock.setAuditExportBatchSize(50);
        exporter = new WormAuditS3ExportService(s3Client, properties, jdbc);
    }

    @Test
    void skipsWhenAuditExportDisabled() {
        properties.getS3().getObjectLock().setApplyToAuditExport(false);
        assertEquals(0, exporter.exportBatch());
        verify(s3Client, never()).putObject(any(PutObjectRequest.class), any(RequestBody.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void exportsBatchWithObjectLockAndAdvancesWatermark() {
        when(jdbc.queryForObject(anyString(), any(SqlParameterSource.class), eq(Long.class)))
                .thenReturn(10L);

        WormAuditEntry entry = new WormAuditEntry(
                11L,
                UUID.fromString("11111111-1111-1111-1111-111111111111"),
                "evaluation.state_transition",
                Instant.parse("2026-11-10T12:00:00Z"),
                "system",
                "evaluation",
                "eval-1",
                "{\"from\":\"QUEUED\",\"to\":\"RUNNING\"}",
                "0".repeat(64),
                "a".repeat(64),
                Instant.parse("2026-11-10T12:00:01Z"));

        when(jdbc.query(anyString(), any(SqlParameterSource.class), any(RowMapper.class)))
                .thenReturn(List.of(entry));
        when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenReturn(PutObjectResponse.builder().build());
        when(jdbc.update(anyString(), any(SqlParameterSource.class))).thenReturn(1);

        assertEquals(1, exporter.exportBatch());

        ArgumentCaptor<PutObjectRequest> putCaptor = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(s3Client).putObject(putCaptor.capture(), any(RequestBody.class));
        PutObjectRequest put = putCaptor.getValue();
        assertEquals("agentbench-artifacts", put.bucket());
        assertEquals("audit/worm/events/11.json", put.key());
        assertEquals(ObjectLockMode.COMPLIANCE, put.objectLockMode());
        assertTrue(put.objectLockRetainUntilDate().isAfter(Instant.now().plusSeconds(360L * 24 * 3600)));

        verify(jdbc).update(anyString(), any(SqlParameterSource.class));
    }

    @Test
    void toJsonEmbedsPayloadAndHashes() {
        WormAuditEntry entry = new WormAuditEntry(
                3L,
                null,
                "suite.read",
                Instant.parse("2026-01-01T00:00:00Z"),
                "alice",
                "suite",
                "s1",
                "{\"k\":1}",
                "b".repeat(64),
                "c".repeat(64),
                Instant.parse("2026-01-01T00:00:01Z"));
        String json = WormAuditS3ExportService.toJson(entry);
        assertTrue(json.contains("\"id\":3"));
        assertTrue(json.contains("\"payload\":{\"k\":1}"));
        assertTrue(json.contains("\"entryHash\":\"" + "c".repeat(64) + "\""));
        assertTrue(json.contains("\"orgId\":null"));
    }
}
