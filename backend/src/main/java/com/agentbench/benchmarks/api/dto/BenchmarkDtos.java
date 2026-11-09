package com.agentbench.benchmarks.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class BenchmarkDtos {

    private BenchmarkDtos() {
    }

    public record CreateBenchmarkRequest(
            @NotBlank @Size(min = 1, max = 120) String name,
            @Size(max = 4000) String description,
            UUID projectId
    ) {
    }

    public record UpdateBenchmarkRequest(
            @NotBlank @Size(min = 1, max = 120) String name,
            @Size(max = 4000) String description,
            UUID projectId
    ) {
    }

    public record BenchmarkResponse(
            UUID id,
            UUID orgId,
            UUID projectId,
            String name,
            String description,
            UUID createdBy,
            Instant createdAt,
            Instant updatedAt
    ) {
    }

    public record CreateVersionRequest(
            @NotBlank @Size(min = 1, max = 40)
            @Pattern(regexp = "^[0-9A-Za-z][0-9A-Za-z._+-]*$", message = "version must be alphanumeric with . _ + -")
            String version,
            @Size(max = 4000) String changelog,
            @Size(max = 80) String scoringProfileKey
    ) {
    }

    public record BenchmarkVersionResponse(
            UUID id,
            UUID benchmarkId,
            UUID orgId,
            String version,
            String status,
            String changelog,
            String scoringProfileKey,
            Instant publishedAt,
            UUID publishedBy,
            boolean hasImmutableSnapshot,
            Instant createdAt,
            Instant updatedAt
    ) {
    }

    public record PublishVersionResponse(
            UUID id,
            String version,
            String status,
            Instant publishedAt,
            UUID publishedBy,
            Map<String, Object> immutableSnapshot
    ) {
    }

    public record CreateTaskRequest(
            @NotBlank @Size(min = 1, max = 80)
            @Pattern(regexp = "^[a-z0-9]+(?:-[a-z0-9]+)*$", message = "slug must be kebab-case")
            String slug,
            @NotBlank @Size(min = 1, max = 200) String title,
            @NotBlank @Size(min = 1, max = 50000) String prompt,
            @Size(max = 20000) String acceptanceCriteria,
            @Size(max = 80) String category,
            @Pattern(regexp = "^$|EASY|MEDIUM|HARD|EXPERT", message = "difficulty must be EASY|MEDIUM|HARD|EXPERT")
            String difficulty,
            List<@Size(max = 40) String> languageTags,
            UUID repoSnapshotId,
            @Pattern(regexp = "^$|^[0-9a-fA-F]{40}$", message = "baselineCommitSha must be 40 hex chars")
            String baselineCommitSha,
            @Size(max = 200000) String referencePatch,
            @Size(max = 20000) String expectedBehavior,
            @Min(1) @Max(86400) Integer timeoutSec,
            @Min(64) @Max(65536) Integer memoryMb,
            BigDecimal cpuLimit,
            @Min(1024) @Max(104857600) Integer maxPatchBytes,
            Map<String, Object> evalConfig,
            Integer ordinal
    ) {
    }

    public record UpdateTaskRequest(
            @NotBlank @Size(min = 1, max = 200) String title,
            @NotBlank @Size(min = 1, max = 50000) String prompt,
            @Size(max = 20000) String acceptanceCriteria,
            @Size(max = 80) String category,
            @Pattern(regexp = "^$|EASY|MEDIUM|HARD|EXPERT") String difficulty,
            List<@Size(max = 40) String> languageTags,
            UUID repoSnapshotId,
            @Pattern(regexp = "^$|^[0-9a-fA-F]{40}$") String baselineCommitSha,
            @Size(max = 200000) String referencePatch,
            @Size(max = 20000) String expectedBehavior,
            @Min(1) @Max(86400) Integer timeoutSec,
            @Min(64) @Max(65536) Integer memoryMb,
            BigDecimal cpuLimit,
            @Min(1024) @Max(104857600) Integer maxPatchBytes,
            Map<String, Object> evalConfig,
            Integer ordinal
    ) {
    }

    public record TaskResponse(
            UUID id,
            UUID benchmarkVersionId,
            UUID orgId,
            String slug,
            String title,
            String prompt,
            String acceptanceCriteria,
            String category,
            String difficulty,
            List<String> languageTags,
            UUID repoSnapshotId,
            String baselineCommitSha,
            String referencePatch,
            String expectedBehavior,
            int timeoutSec,
            int memoryMb,
            BigDecimal cpuLimit,
            int maxPatchBytes,
            Map<String, Object> evalConfig,
            int ordinal,
            boolean hasVisibleSuite,
            boolean hasHiddenSuite,
            SuiteSummary visibleSuite,
            SuiteSummary hiddenSuite,
            Instant createdAt,
            Instant updatedAt
    ) {
    }

    /**
     * Suite summary returned on task GETs. Sensitive hidden fields are null unless the
     * caller is allowed to view hidden fixture content (DEVELOPER+/AUTHOR+).
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SuiteSummary(
            UUID id,
            String visibility,
            String framework,
            Integer caseCount,
            boolean contentRestricted,
            String storageUri,
            String entryCommand,
            String contentDigest,
            String fixtureContent,
            Map<String, Object> expectedOutcomes
    ) {
    }

    public record UpsertSuiteRequest(
            @NotBlank @Size(min = 1, max = 40) String framework,
            @Size(max = 1000) String storageUri,
            @Size(max = 2000) String entryCommand,
            @Size(max = 128) String contentDigest,
            @Size(max = 500000) String fixtureContent,
            Map<String, Object> expectedOutcomes,
            @Min(0) @Max(100000) Integer caseCount
    ) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SuiteResponse(
            UUID id,
            UUID taskId,
            String visibility,
            String framework,
            Integer caseCount,
            boolean contentRestricted,
            String storageUri,
            String entryCommand,
            String contentDigest,
            String fixtureContent,
            Map<String, Object> expectedOutcomes,
            Instant createdAt,
            Instant updatedAt
    ) {
    }

    /**
     * Agent-facing task context. Intentionally omit-list: no hidden suite fields,
     * no reference patches, no expected outcomes, no acceptance criteria internals
     * that leak hidden cases. Used to prepare M4 adapter payloads.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AgentTaskContext(
            UUID taskId,
            UUID benchmarkVersionId,
            String slug,
            String title,
            String prompt,
            String category,
            String difficulty,
            List<String> languageTags,
            String baselineCommitSha,
            UUID repoSnapshotId,
            int timeoutSec,
            int memoryMb,
            BigDecimal cpuLimit,
            int maxPatchBytes,
            Map<String, Object> evalConfig,
            VisibleSuiteContext visibleSuite
    ) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record VisibleSuiteContext(
            UUID id,
            String framework,
            String entryCommand,
            Integer caseCount
    ) {
    }
}
