package com.agentbench.benchmarks.service;

import com.agentbench.benchmarks.api.dto.BenchmarkDtos;
import com.agentbench.benchmarks.domain.Benchmark;
import com.agentbench.benchmarks.domain.BenchmarkStatus;
import com.agentbench.benchmarks.domain.BenchmarkVersion;
import com.agentbench.benchmarks.domain.SuiteVisibility;
import com.agentbench.benchmarks.domain.Task;
import com.agentbench.benchmarks.domain.TaskDifficulty;
import com.agentbench.benchmarks.domain.TestSuite;
import com.agentbench.benchmarks.infra.BenchmarkRepository;
import com.agentbench.identity.domain.Membership;
import com.agentbench.identity.domain.OrgRole;
import com.agentbench.identity.security.OrgAccessService;
import com.agentbench.identity.security.UserPrincipal;
import com.agentbench.platform.error.ApiException;
import com.agentbench.platform.infra.storage.ArtifactStorage;
import com.agentbench.projects.infra.ProjectRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class BenchmarkService {

    /**
     * Hidden fixture content is readable/writable by AUTHOR+ mapped to DEVELOPER+
     * (ADR-006). VIEWER/EVALUATOR never receive fixture bodies, storage URIs,
     * entry commands, digests, or expected outcomes. Agent-facing projections never
     * include hidden fields regardless of role.
     */
    private static final OrgRole HIDDEN_CONTENT_READ_ROLE = OrgRole.DEVELOPER;
    private static final OrgRole HIDDEN_SUITE_WRITE_ROLE = OrgRole.DEVELOPER;

    private final BenchmarkRepository benchmarkRepository;
    private final ProjectRepository projectRepository;
    private final OrgAccessService orgAccessService;
    private final ArtifactStorage artifactStorage;
    private final ObjectMapper objectMapper;

    public BenchmarkService(
            BenchmarkRepository benchmarkRepository,
            ProjectRepository projectRepository,
            OrgAccessService orgAccessService,
            ArtifactStorage artifactStorage,
            ObjectMapper objectMapper) {
        this.benchmarkRepository = benchmarkRepository;
        this.projectRepository = projectRepository;
        this.orgAccessService = orgAccessService;
        this.artifactStorage = artifactStorage;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public BenchmarkDtos.BenchmarkResponse createBenchmark(UUID orgId, BenchmarkDtos.CreateBenchmarkRequest request) {
        orgAccessService.requireMembership(orgId, OrgRole.DEVELOPER);
        UserPrincipal principal = orgAccessService.requirePrincipal();
        UUID projectId = validateProject(orgId, request.projectId());
        try {
            Benchmark benchmark = benchmarkRepository.insertBenchmark(
                    orgId,
                    projectId,
                    request.name().trim(),
                    blankToEmpty(request.description()),
                    principal.userId()
            );
            return toBenchmark(benchmark);
        } catch (DuplicateKeyException ex) {
            throw ApiException.conflict("Benchmark name already exists in organization");
        }
    }

    @Transactional(readOnly = true)
    public List<BenchmarkDtos.BenchmarkResponse> listBenchmarks(UUID orgId) {
        orgAccessService.requireMembership(orgId, OrgRole.VIEWER);
        return benchmarkRepository.listBenchmarks(orgId).stream().map(BenchmarkService::toBenchmark).toList();
    }

    @Transactional(readOnly = true)
    public BenchmarkDtos.BenchmarkResponse getBenchmark(UUID orgId, UUID benchmarkId) {
        orgAccessService.requireMembership(orgId, OrgRole.VIEWER);
        return toBenchmark(requireBenchmark(orgId, benchmarkId));
    }

    @Transactional
    public BenchmarkDtos.BenchmarkResponse updateBenchmark(
            UUID orgId,
            UUID benchmarkId,
            BenchmarkDtos.UpdateBenchmarkRequest request) {
        orgAccessService.requireMembership(orgId, OrgRole.DEVELOPER);
        requireBenchmark(orgId, benchmarkId);
        UUID projectId = validateProject(orgId, request.projectId());
        try {
            return toBenchmark(benchmarkRepository.updateBenchmark(
                    orgId,
                    benchmarkId,
                    request.name().trim(),
                    blankToEmpty(request.description()),
                    projectId
            ));
        } catch (DuplicateKeyException ex) {
            throw ApiException.conflict("Benchmark name already exists in organization");
        }
    }

    @Transactional
    public void deleteBenchmark(UUID orgId, UUID benchmarkId) {
        orgAccessService.requireMembership(orgId, OrgRole.ADMIN);
        requireBenchmark(orgId, benchmarkId);
        if (benchmarkRepository.hasPublishedVersions(orgId, benchmarkId)) {
            throw ApiException.conflict("Cannot delete benchmark with published or retired versions");
        }
        benchmarkRepository.deleteBenchmark(orgId, benchmarkId);
    }

    @Transactional
    public BenchmarkDtos.BenchmarkVersionResponse createVersion(
            UUID orgId,
            UUID benchmarkId,
            BenchmarkDtos.CreateVersionRequest request) {
        orgAccessService.requireMembership(orgId, OrgRole.DEVELOPER);
        requireBenchmark(orgId, benchmarkId);
        try {
            BenchmarkVersion version = benchmarkRepository.insertVersion(
                    orgId,
                    benchmarkId,
                    request.version().trim(),
                    blankToEmpty(request.changelog()),
                    request.scoringProfileKey() == null || request.scoringProfileKey().isBlank()
                            ? "default-v1"
                            : request.scoringProfileKey().trim()
            );
            return toVersion(version);
        } catch (DuplicateKeyException ex) {
            throw ApiException.conflict("Version already exists for this benchmark");
        }
    }

    @Transactional(readOnly = true)
    public List<BenchmarkDtos.BenchmarkVersionResponse> listVersions(UUID orgId, UUID benchmarkId) {
        orgAccessService.requireMembership(orgId, OrgRole.VIEWER);
        requireBenchmark(orgId, benchmarkId);
        return benchmarkRepository.listVersions(orgId, benchmarkId).stream()
                .map(BenchmarkService::toVersion)
                .toList();
    }

    @Transactional(readOnly = true)
    public BenchmarkDtos.BenchmarkVersionResponse getVersion(UUID orgId, UUID versionId) {
        orgAccessService.requireMembership(orgId, OrgRole.VIEWER);
        return toVersion(requireVersion(orgId, versionId));
    }

    @Transactional
    public BenchmarkDtos.PublishVersionResponse publishVersion(UUID orgId, UUID versionId) {
        orgAccessService.requireMembership(orgId, OrgRole.DEVELOPER);
        UserPrincipal principal = orgAccessService.requirePrincipal();
        BenchmarkVersion version = requireVersion(orgId, versionId);
        if (version.status() != BenchmarkStatus.DRAFT) {
            throw ApiException.conflict("Only DRAFT versions can be published");
        }
        List<Task> tasks = benchmarkRepository.listTasks(orgId, versionId);
        if (tasks.isEmpty()) {
            throw ApiException.validation("Cannot publish a version with no tasks");
        }
        for (Task task : tasks) {
            Optional<TestSuite> visible = benchmarkRepository.findSuite(orgId, task.id(), SuiteVisibility.VISIBLE);
            if (visible.isEmpty()) {
                throw ApiException.validation("Task '" + task.slug() + "' requires a visible test suite before publish");
            }
        }
        Map<String, Object> snapshot = buildImmutableSnapshot(orgId, version, tasks);
        String snapshotJson = writeJson(snapshot);
        BenchmarkVersion published = benchmarkRepository.publishVersion(
                orgId,
                versionId,
                principal.userId(),
                snapshotJson
        );
        if (published.status() != BenchmarkStatus.PUBLISHED) {
            throw ApiException.conflict("Version was not in DRAFT state at publish time");
        }
        return new BenchmarkDtos.PublishVersionResponse(
                published.id(),
                published.version(),
                published.status().name(),
                published.publishedAt(),
                published.publishedBy(),
                snapshot
        );
    }

    @Transactional
    public BenchmarkDtos.TaskResponse createTask(
            UUID orgId,
            UUID versionId,
            BenchmarkDtos.CreateTaskRequest request) {
        orgAccessService.requireMembership(orgId, OrgRole.DEVELOPER);
        BenchmarkVersion version = requireVersion(orgId, versionId);
        requireDraft(version);
        validateRepoSnapshot(orgId, request.repoSnapshotId());
        try {
            Task task = benchmarkRepository.insertTask(
                    orgId,
                    versionId,
                    request.slug().trim(),
                    request.title().trim(),
                    request.prompt().trim(),
                    blankToEmpty(request.acceptanceCriteria()),
                    blankToNull(request.category()),
                    TaskDifficulty.fromString(request.difficulty()),
                    writeJson(normalizeTags(request.languageTags())),
                    request.repoSnapshotId(),
                    blankToNull(request.baselineCommitSha()),
                    blankToEmpty(request.referencePatch()),
                    blankToEmpty(request.expectedBehavior()),
                    request.timeoutSec() == null ? 1800 : request.timeoutSec(),
                    request.memoryMb() == null ? 2048 : request.memoryMb(),
                    request.cpuLimit(),
                    request.maxPatchBytes() == null ? 5_242_880 : request.maxPatchBytes(),
                    writeJson(request.evalConfig() == null ? Map.of() : request.evalConfig()),
                    request.ordinal() == null ? 0 : request.ordinal()
            );
            return toTaskResponse(orgId, task, canReadHiddenContent(orgId));
        } catch (DuplicateKeyException ex) {
            throw ApiException.conflict("Task slug already exists in this version");
        }
    }

    @Transactional(readOnly = true)
    public List<BenchmarkDtos.TaskResponse> listTasks(UUID orgId, UUID versionId) {
        orgAccessService.requireMembership(orgId, OrgRole.VIEWER);
        requireVersion(orgId, versionId);
        boolean includeHidden = canReadHiddenContent(orgId);
        return benchmarkRepository.listTasks(orgId, versionId).stream()
                .map(task -> toTaskResponse(orgId, task, includeHidden))
                .toList();
    }

    @Transactional(readOnly = true)
    public BenchmarkDtos.TaskResponse getTask(UUID orgId, UUID taskId) {
        orgAccessService.requireMembership(orgId, OrgRole.VIEWER);
        Task task = requireTask(orgId, taskId);
        return toTaskResponse(orgId, task, canReadHiddenContent(orgId));
    }

    @Transactional
    public BenchmarkDtos.TaskResponse updateTask(
            UUID orgId,
            UUID taskId,
            BenchmarkDtos.UpdateTaskRequest request) {
        orgAccessService.requireMembership(orgId, OrgRole.DEVELOPER);
        Task existing = requireTask(orgId, taskId);
        BenchmarkVersion version = requireVersion(orgId, existing.benchmarkVersionId());
        requireDraft(version);
        validateRepoSnapshot(orgId, request.repoSnapshotId());
        Task task = benchmarkRepository.updateTask(
                orgId,
                taskId,
                request.title().trim(),
                request.prompt().trim(),
                blankToEmpty(request.acceptanceCriteria()),
                blankToNull(request.category()),
                TaskDifficulty.fromString(request.difficulty()),
                writeJson(normalizeTags(request.languageTags())),
                request.repoSnapshotId(),
                blankToNull(request.baselineCommitSha()),
                blankToEmpty(request.referencePatch()),
                blankToEmpty(request.expectedBehavior()),
                request.timeoutSec() == null ? existing.timeoutSec() : request.timeoutSec(),
                request.memoryMb() == null ? existing.memoryMb() : request.memoryMb(),
                request.cpuLimit(),
                request.maxPatchBytes() == null ? existing.maxPatchBytes() : request.maxPatchBytes(),
                writeJson(request.evalConfig() == null ? Map.of() : request.evalConfig()),
                request.ordinal() == null ? existing.ordinal() : request.ordinal()
        );
        return toTaskResponse(orgId, task, canReadHiddenContent(orgId));
    }

    @Transactional
    public void deleteTask(UUID orgId, UUID taskId) {
        orgAccessService.requireMembership(orgId, OrgRole.DEVELOPER);
        Task task = requireTask(orgId, taskId);
        requireDraft(requireVersion(orgId, task.benchmarkVersionId()));
        benchmarkRepository.deleteTask(orgId, taskId);
    }

    @Transactional
    public BenchmarkDtos.SuiteResponse upsertVisibleSuite(
            UUID orgId,
            UUID taskId,
            BenchmarkDtos.UpsertSuiteRequest request) {
        orgAccessService.requireMembership(orgId, OrgRole.DEVELOPER);
        return upsertSuite(orgId, taskId, SuiteVisibility.VISIBLE, request, true);
    }

    @Transactional
    public BenchmarkDtos.SuiteResponse upsertHiddenSuite(
            UUID orgId,
            UUID taskId,
            BenchmarkDtos.UpsertSuiteRequest request) {
        orgAccessService.requireMembership(orgId, HIDDEN_SUITE_WRITE_ROLE);
        return upsertSuite(orgId, taskId, SuiteVisibility.HIDDEN, request, canReadHiddenContent(orgId));
    }

    @Transactional(readOnly = true)
    public BenchmarkDtos.SuiteResponse getVisibleSuite(UUID orgId, UUID taskId) {
        orgAccessService.requireMembership(orgId, OrgRole.VIEWER);
        requireTask(orgId, taskId);
        TestSuite suite = benchmarkRepository.findSuite(orgId, taskId, SuiteVisibility.VISIBLE)
                .orElseThrow(() -> ApiException.notFound("Visible suite not found"));
        return toSuiteResponse(suite, true);
    }

    @Transactional(readOnly = true)
    public BenchmarkDtos.SuiteResponse getHiddenSuite(UUID orgId, UUID taskId) {
        Membership membership = orgAccessService.requireMembership(orgId, OrgRole.VIEWER);
        requireTask(orgId, taskId);
        TestSuite suite = benchmarkRepository.findSuite(orgId, taskId, SuiteVisibility.HIDDEN)
                .orElseThrow(() -> ApiException.notFound("Hidden suite not found"));
        boolean allowContent = membership.role().atLeast(HIDDEN_CONTENT_READ_ROLE);
        return toSuiteResponse(suite, allowContent);
    }

    /**
     * Agent-facing allowlist projection — never includes hidden suite data,
     * reference patches, expected outcomes, or acceptance criteria.
     */
    @Transactional(readOnly = true)
    public BenchmarkDtos.AgentTaskContext getAgentTaskContext(UUID orgId, UUID taskId) {
        orgAccessService.requireMembership(orgId, OrgRole.VIEWER);
        Task task = requireTask(orgId, taskId);
        Optional<TestSuite> visible = benchmarkRepository.findSuite(orgId, taskId, SuiteVisibility.VISIBLE);
        BenchmarkDtos.VisibleSuiteContext visibleCtx = visible
                .map(suite -> new BenchmarkDtos.VisibleSuiteContext(
                        suite.id(),
                        suite.framework(),
                        suite.entryCommand(),
                        suite.caseCount()
                ))
                .orElse(null);
        return new BenchmarkDtos.AgentTaskContext(
                task.id(),
                task.benchmarkVersionId(),
                task.slug(),
                task.title(),
                task.prompt(),
                task.category(),
                task.difficulty() == null ? null : task.difficulty().name(),
                task.languageTags(),
                task.baselineCommitSha(),
                task.repoSnapshotId(),
                task.timeoutSec(),
                task.memoryMb(),
                task.cpuLimit(),
                task.maxPatchBytes(),
                readMap(task.evalConfigJson()),
                visibleCtx
        );
    }

    private BenchmarkDtos.SuiteResponse upsertSuite(
            UUID orgId,
            UUID taskId,
            SuiteVisibility visibility,
            BenchmarkDtos.UpsertSuiteRequest request,
            boolean returnContent) {
        Task task = requireTask(orgId, taskId);
        requireDraft(requireVersion(orgId, task.benchmarkVersionId()));

        String fixtureContent = blankToEmpty(request.fixtureContent());
        String storageUri = blankToEmpty(request.storageUri());
        String contentDigest = blankToEmpty(request.contentDigest());
        if (!fixtureContent.isEmpty()) {
            byte[] bytes = fixtureContent.getBytes(StandardCharsets.UTF_8);
            if (contentDigest.isEmpty()) {
                contentDigest = sha256Hex(bytes);
            }
            if (storageUri.isEmpty()) {
                String key = "orgs/%s/tasks/%s/suites/%s/%s".formatted(
                        orgId, taskId, visibility.name().toLowerCase(), contentDigest);
                storageUri = artifactStorage.store(
                        key,
                        new ByteArrayInputStream(bytes),
                        bytes.length,
                        "text/plain"
                );
            }
        }

        TestSuite suite = benchmarkRepository.upsertSuite(
                orgId,
                taskId,
                visibility,
                request.framework().trim(),
                storageUri,
                blankToEmpty(request.entryCommand()),
                contentDigest,
                fixtureContent,
                writeJson(request.expectedOutcomes() == null ? Map.of() : request.expectedOutcomes()),
                request.caseCount()
        );
        return toSuiteResponse(suite, returnContent || visibility == SuiteVisibility.VISIBLE);
    }

    private Map<String, Object> buildImmutableSnapshot(UUID orgId, BenchmarkVersion version, List<Task> tasks) {
        Benchmark benchmark = requireBenchmark(orgId, version.benchmarkId());
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("schema", "agentbench.benchmark-version.v1");
        Map<String, Object> benchmarkMap = new LinkedHashMap<>();
        benchmarkMap.put("id", benchmark.id().toString());
        benchmarkMap.put("name", benchmark.name());
        benchmarkMap.put("description", benchmark.description());
        benchmarkMap.put("projectId", benchmark.projectId() == null ? null : benchmark.projectId().toString());
        root.put("benchmark", benchmarkMap);
        Map<String, Object> versionMap = new LinkedHashMap<>();
        versionMap.put("id", version.id().toString());
        versionMap.put("version", version.version());
        versionMap.put("changelog", version.changelog());
        versionMap.put("scoringProfileKey", version.scoringProfileKey());
        root.put("version", versionMap);
        List<Map<String, Object>> taskSnapshots = tasks.stream().map(task -> {
            Map<String, Object> taskMap = new LinkedHashMap<>();
            taskMap.put("id", task.id().toString());
            taskMap.put("slug", task.slug());
            taskMap.put("title", task.title());
            taskMap.put("prompt", task.prompt());
            taskMap.put("acceptanceCriteria", task.acceptanceCriteria());
            taskMap.put("category", task.category());
            taskMap.put("difficulty", task.difficulty() == null ? null : task.difficulty().name());
            taskMap.put("languageTags", task.languageTags());
            taskMap.put("repoSnapshotId", task.repoSnapshotId() == null ? null : task.repoSnapshotId().toString());
            taskMap.put("baselineCommitSha", task.baselineCommitSha());
            taskMap.put("referencePatch", task.referencePatch());
            taskMap.put("expectedBehavior", task.expectedBehavior());
            taskMap.put("timeoutSec", task.timeoutSec());
            taskMap.put("memoryMb", task.memoryMb());
            taskMap.put("cpuLimit", task.cpuLimit());
            taskMap.put("maxPatchBytes", task.maxPatchBytes());
            taskMap.put("evalConfig", readMap(task.evalConfigJson()));
            taskMap.put("ordinal", task.ordinal());
            List<Map<String, Object>> suites = benchmarkRepository.listSuitesForTask(orgId, task.id()).stream()
                    .map(suite -> {
                        Map<String, Object> suiteMap = new LinkedHashMap<>();
                        suiteMap.put("id", suite.id().toString());
                        suiteMap.put("visibility", suite.visibility().name());
                        suiteMap.put("framework", suite.framework());
                        suiteMap.put("storageUri", suite.storageUri());
                        suiteMap.put("entryCommand", suite.entryCommand());
                        suiteMap.put("contentDigest", suite.contentDigest());
                        suiteMap.put("fixtureContent", suite.fixtureContent());
                        suiteMap.put("expectedOutcomes", readMap(suite.expectedOutcomesJson()));
                        suiteMap.put("caseCount", suite.caseCount());
                        return suiteMap;
                    })
                    .toList();
            taskMap.put("suites", suites);
            return taskMap;
        }).toList();
        root.put("tasks", taskSnapshots);
        return root;
    }

    private boolean canReadHiddenContent(UUID orgId) {
        Membership membership = orgAccessService.requireMembership(orgId, OrgRole.VIEWER);
        return membership.role().atLeast(HIDDEN_CONTENT_READ_ROLE);
    }

    private UUID validateProject(UUID orgId, UUID projectId) {
        if (projectId == null) {
            return null;
        }
        return projectRepository.findById(orgId, projectId)
                .orElseThrow(() -> ApiException.notFound("Project not found"))
                .id();
    }

    private void validateRepoSnapshot(UUID orgId, UUID repoSnapshotId) {
        if (repoSnapshotId == null) {
            return;
        }
        projectRepository.findSnapshot(orgId, repoSnapshotId)
                .orElseThrow(() -> ApiException.notFound("Repo snapshot not found"));
    }

    private Benchmark requireBenchmark(UUID orgId, UUID benchmarkId) {
        return benchmarkRepository.findBenchmark(orgId, benchmarkId)
                .orElseThrow(() -> ApiException.notFound("Benchmark not found"));
    }

    private BenchmarkVersion requireVersion(UUID orgId, UUID versionId) {
        return benchmarkRepository.findVersion(orgId, versionId)
                .orElseThrow(() -> ApiException.notFound("Benchmark version not found"));
    }

    private Task requireTask(UUID orgId, UUID taskId) {
        return benchmarkRepository.findTask(orgId, taskId)
                .orElseThrow(() -> ApiException.notFound("Task not found"));
    }

    private static void requireDraft(BenchmarkVersion version) {
        if (version.status().isImmutable()) {
            throw ApiException.conflict("Published/retired benchmark versions are immutable");
        }
    }

    private BenchmarkDtos.TaskResponse toTaskResponse(UUID orgId, Task task, boolean includeHiddenContent) {
        Optional<TestSuite> visible = benchmarkRepository.findSuite(orgId, task.id(), SuiteVisibility.VISIBLE);
        Optional<TestSuite> hidden = benchmarkRepository.findSuite(orgId, task.id(), SuiteVisibility.HIDDEN);
        return new BenchmarkDtos.TaskResponse(
                task.id(),
                task.benchmarkVersionId(),
                task.orgId(),
                task.slug(),
                task.title(),
                task.prompt(),
                task.acceptanceCriteria(),
                task.category(),
                task.difficulty() == null ? null : task.difficulty().name(),
                task.languageTags(),
                task.repoSnapshotId(),
                task.baselineCommitSha(),
                task.referencePatch(),
                task.expectedBehavior(),
                task.timeoutSec(),
                task.memoryMb(),
                task.cpuLimit(),
                task.maxPatchBytes(),
                readMap(task.evalConfigJson()),
                task.ordinal(),
                visible.isPresent(),
                hidden.isPresent(),
                visible.map(suite -> toSuiteSummary(suite, true)).orElse(null),
                hidden.map(suite -> toSuiteSummary(suite, includeHiddenContent)).orElse(null),
                task.createdAt(),
                task.updatedAt()
        );
    }

    private BenchmarkDtos.SuiteSummary toSuiteSummary(TestSuite suite, boolean includeContent) {
        if (!includeContent && suite.visibility() == SuiteVisibility.HIDDEN) {
            return new BenchmarkDtos.SuiteSummary(
                    suite.id(),
                    suite.visibility().name(),
                    suite.framework(),
                    suite.caseCount(),
                    true,
                    null,
                    null,
                    null,
                    null,
                    null
            );
        }
        return new BenchmarkDtos.SuiteSummary(
                suite.id(),
                suite.visibility().name(),
                suite.framework(),
                suite.caseCount(),
                false,
                suite.storageUri(),
                suite.entryCommand(),
                suite.contentDigest(),
                suite.fixtureContent(),
                readMap(suite.expectedOutcomesJson())
        );
    }

    private BenchmarkDtos.SuiteResponse toSuiteResponse(TestSuite suite, boolean includeContent) {
        if (!includeContent && suite.visibility() == SuiteVisibility.HIDDEN) {
            return new BenchmarkDtos.SuiteResponse(
                    suite.id(),
                    suite.taskId(),
                    suite.visibility().name(),
                    suite.framework(),
                    suite.caseCount(),
                    true,
                    null,
                    null,
                    null,
                    null,
                    null,
                    suite.createdAt(),
                    suite.updatedAt()
            );
        }
        return new BenchmarkDtos.SuiteResponse(
                suite.id(),
                suite.taskId(),
                suite.visibility().name(),
                suite.framework(),
                suite.caseCount(),
                false,
                suite.storageUri(),
                suite.entryCommand(),
                suite.contentDigest(),
                suite.fixtureContent(),
                readMap(suite.expectedOutcomesJson()),
                suite.createdAt(),
                suite.updatedAt()
        );
    }

    private static BenchmarkDtos.BenchmarkResponse toBenchmark(Benchmark benchmark) {
        return new BenchmarkDtos.BenchmarkResponse(
                benchmark.id(),
                benchmark.orgId(),
                benchmark.projectId(),
                benchmark.name(),
                benchmark.description(),
                benchmark.createdBy(),
                benchmark.createdAt(),
                benchmark.updatedAt()
        );
    }

    private static BenchmarkDtos.BenchmarkVersionResponse toVersion(BenchmarkVersion version) {
        return new BenchmarkDtos.BenchmarkVersionResponse(
                version.id(),
                version.benchmarkId(),
                version.orgId(),
                version.version(),
                version.status().name(),
                version.changelog(),
                version.scoringProfileKey(),
                version.publishedAt(),
                version.publishedBy(),
                version.immutableSnapshotJson() != null && !version.immutableSnapshotJson().isBlank(),
                version.createdAt(),
                version.updatedAt()
        );
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw ApiException.validation("Invalid JSON payload");
        }
    }

    private Map<String, Object> readMap(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<>() {
            });
        } catch (Exception ex) {
            return Map.of();
        }
    }

    private static List<String> normalizeTags(List<String> tags) {
        if (tags == null) {
            return List.of();
        }
        return tags.stream()
                .filter(t -> t != null && !t.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
    }

    private static String blankToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(bytes));
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }
}
