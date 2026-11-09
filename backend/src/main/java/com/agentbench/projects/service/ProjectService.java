package com.agentbench.projects.service;

import com.agentbench.identity.domain.OrgRole;
import com.agentbench.identity.security.OrgAccessService;
import com.agentbench.identity.security.UserPrincipal;
import com.agentbench.identity.service.SecretService;
import com.agentbench.platform.error.ApiException;
import com.agentbench.projects.api.dto.ProjectDtos;
import com.agentbench.projects.domain.Project;
import com.agentbench.projects.domain.ProjectRepositoryConfig;
import com.agentbench.projects.domain.RepoSnapshot;
import com.agentbench.projects.github.GitHubRepositoryValidator;
import com.agentbench.projects.infra.ProjectRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class ProjectService {

    private final ProjectRepository projectRepository;
    private final OrgAccessService orgAccessService;
    private final SecretService secretService;
    private final GitHubRepositoryValidator gitHubRepositoryValidator;

    public ProjectService(
            ProjectRepository projectRepository,
            OrgAccessService orgAccessService,
            SecretService secretService,
            GitHubRepositoryValidator gitHubRepositoryValidator) {
        this.projectRepository = projectRepository;
        this.orgAccessService = orgAccessService;
        this.secretService = secretService;
        this.gitHubRepositoryValidator = gitHubRepositoryValidator;
    }

    @Transactional
    public ProjectDtos.ProjectResponse create(UUID orgId, ProjectDtos.CreateProjectRequest request) {
        orgAccessService.requireMembership(orgId, OrgRole.DEVELOPER);
        UserPrincipal principal = orgAccessService.requirePrincipal();
        try {
            Project project = projectRepository.insert(
                    orgId,
                    request.name().trim(),
                    request.description() == null ? "" : request.description().trim(),
                    principal.userId()
            );
            return toProject(project);
        } catch (DuplicateKeyException ex) {
            throw ApiException.conflict("Project name already exists in organization");
        }
    }

    @Transactional(readOnly = true)
    public List<ProjectDtos.ProjectResponse> list(UUID orgId) {
        orgAccessService.requireMembership(orgId, OrgRole.VIEWER);
        return projectRepository.listByOrg(orgId).stream().map(ProjectService::toProject).toList();
    }

    @Transactional(readOnly = true)
    public ProjectDtos.ProjectResponse get(UUID orgId, UUID projectId) {
        orgAccessService.requireMembership(orgId, OrgRole.VIEWER);
        return toProject(requireProject(orgId, projectId));
    }

    @Transactional
    public ProjectDtos.ProjectResponse update(UUID orgId, UUID projectId, ProjectDtos.UpdateProjectRequest request) {
        orgAccessService.requireMembership(orgId, OrgRole.DEVELOPER);
        requireProject(orgId, projectId);
        try {
            return toProject(projectRepository.update(
                    orgId,
                    projectId,
                    request.name().trim(),
                    request.description() == null ? "" : request.description().trim()
            ));
        } catch (DuplicateKeyException ex) {
            throw ApiException.conflict("Project name already exists in organization");
        }
    }

    @Transactional
    public void delete(UUID orgId, UUID projectId) {
        orgAccessService.requireMembership(orgId, OrgRole.ADMIN);
        requireProject(orgId, projectId);
        projectRepository.delete(orgId, projectId);
    }

    @Transactional
    public ProjectDtos.RepositoryResponse upsertRepository(
            UUID orgId,
            UUID projectId,
            ProjectDtos.UpsertRepositoryRequest request) {
        orgAccessService.requireMembership(orgId, OrgRole.DEVELOPER);
        requireProject(orgId, projectId);

        if (request.secretRefId() != null) {
            // Ensures secret belongs to this org (repository enforces org_id).
            secretService.decryptForOrg(orgId, request.secretRefId());
        }

        String commitSha = blankToNull(request.commitSha());
        ProjectRepositoryConfig config = projectRepository.upsertRepository(
                orgId,
                projectId,
                request.gitUrl().trim(),
                request.defaultBranch().trim(),
                commitSha,
                blankToNull(request.language()),
                blankToNull(request.buildCommand()),
                blankToNull(request.testCommand()),
                request.secretRefId()
        );

        boolean shouldValidate = request.validate() == null || request.validate();
        if (shouldValidate) {
            Optional<String> token = Optional.empty();
            if (request.secretRefId() != null) {
                token = Optional.of(secretService.decryptForOrg(orgId, request.secretRefId()));
            }
            var result = gitHubRepositoryValidator.validate(request.gitUrl().trim(), token);
            projectRepository.updateValidation(orgId, projectId, result.status(), result.message());
            config = projectRepository.findRepository(orgId, projectId).orElse(config);
        }

        return toRepo(config);
    }

    @Transactional(readOnly = true)
    public ProjectDtos.RepositoryResponse getRepository(UUID orgId, UUID projectId) {
        orgAccessService.requireMembership(orgId, OrgRole.VIEWER);
        requireProject(orgId, projectId);
        return projectRepository.findRepository(orgId, projectId)
                .map(ProjectService::toRepo)
                .orElseThrow(() -> ApiException.notFound("Repository configuration not found"));
    }

    @Transactional
    public ProjectDtos.RepositoryResponse validateRepository(UUID orgId, UUID projectId) {
        orgAccessService.requireMembership(orgId, OrgRole.DEVELOPER);
        requireProject(orgId, projectId);
        ProjectRepositoryConfig config = projectRepository.findRepository(orgId, projectId)
                .orElseThrow(() -> ApiException.notFound("Repository configuration not found"));
        Optional<String> token = Optional.empty();
        if (config.secretRefId() != null) {
            token = Optional.of(secretService.decryptForOrg(orgId, config.secretRefId()));
        }
        var result = gitHubRepositoryValidator.validate(config.gitUrl(), token);
        projectRepository.updateValidation(orgId, projectId, result.status(), result.message());
        return toRepo(projectRepository.findRepository(orgId, projectId).orElseThrow());
    }

    @Transactional
    public ProjectDtos.SnapshotResponse createSnapshot(
            UUID orgId,
            UUID projectId,
            ProjectDtos.CreateSnapshotRequest request) {
        orgAccessService.requireMembership(orgId, OrgRole.DEVELOPER);
        Project project = requireProject(orgId, projectId);
        Optional<ProjectRepositoryConfig> repo = projectRepository.findRepository(orgId, projectId);
        String gitUrl = request.gitUrl() != null && !request.gitUrl().isBlank()
                ? request.gitUrl().trim()
                : repo.map(ProjectRepositoryConfig::gitUrl).orElse(null);
        String commitSha = blankToNull(request.commitSha());
        if (commitSha == null) {
            commitSha = repo.map(ProjectRepositoryConfig::commitSha).orElse(null);
        }
        String branch = request.branch() != null && !request.branch().isBlank()
                ? request.branch().trim()
                : repo.map(ProjectRepositoryConfig::defaultBranch).orElse(null);
        RepoSnapshot snapshot = projectRepository.insertSnapshot(
                orgId,
                project.id(),
                gitUrl,
                commitSha,
                branch,
                request.contentDigest(),
                request.storageUri()
        );
        return toSnapshot(snapshot);
    }

    @Transactional(readOnly = true)
    public List<ProjectDtos.SnapshotResponse> listSnapshots(UUID orgId, UUID projectId) {
        orgAccessService.requireMembership(orgId, OrgRole.VIEWER);
        requireProject(orgId, projectId);
        return projectRepository.listSnapshots(orgId, projectId).stream()
                .map(ProjectService::toSnapshot)
                .toList();
    }

    @Transactional(readOnly = true)
    public ProjectDtos.SnapshotResponse getSnapshot(UUID orgId, UUID snapshotId) {
        orgAccessService.requireMembership(orgId, OrgRole.VIEWER);
        return projectRepository.findSnapshot(orgId, snapshotId)
                .map(ProjectService::toSnapshot)
                .orElseThrow(() -> ApiException.notFound("Snapshot not found"));
    }

    private Project requireProject(UUID orgId, UUID projectId) {
        return projectRepository.findById(orgId, projectId)
                .orElseThrow(() -> ApiException.notFound("Project not found"));
    }

    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private static ProjectDtos.ProjectResponse toProject(Project project) {
        return new ProjectDtos.ProjectResponse(
                project.id(),
                project.orgId(),
                project.name(),
                project.description(),
                project.createdBy(),
                project.createdAt(),
                project.updatedAt()
        );
    }

    private static ProjectDtos.RepositoryResponse toRepo(ProjectRepositoryConfig config) {
        return new ProjectDtos.RepositoryResponse(
                config.id(),
                config.projectId(),
                config.gitUrl(),
                config.defaultBranch(),
                config.commitSha(),
                config.language(),
                config.buildCommand(),
                config.testCommand(),
                config.secretRefId(),
                config.secretRefId() != null,
                config.validationStatus(),
                config.validationMessage(),
                config.lastValidatedAt(),
                config.updatedAt()
        );
    }

    private static ProjectDtos.SnapshotResponse toSnapshot(RepoSnapshot snapshot) {
        return new ProjectDtos.SnapshotResponse(
                snapshot.id(),
                snapshot.projectId(),
                snapshot.gitUrl(),
                snapshot.commitSha(),
                snapshot.branch(),
                snapshot.contentDigest(),
                snapshot.storageUri(),
                snapshot.createdAt()
        );
    }
}
