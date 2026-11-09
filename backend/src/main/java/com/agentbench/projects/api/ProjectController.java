package com.agentbench.projects.api;

import com.agentbench.platform.api.ApiPaths;
import com.agentbench.projects.api.dto.ProjectDtos;
import com.agentbench.projects.service.ProjectService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping(ApiPaths.API_V1 + "/orgs/{orgId}")
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "Projects", description = "Projects, GitHub repo config, and snapshots")
public class ProjectController {

    private final ProjectService projectService;

    public ProjectController(ProjectService projectService) {
        this.projectService = projectService;
    }

    @PostMapping(value = "/projects", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create project")
    public ProjectDtos.ProjectResponse create(
            @PathVariable UUID orgId,
            @Valid @RequestBody ProjectDtos.CreateProjectRequest request) {
        return projectService.create(orgId, request);
    }

    @GetMapping(value = "/projects", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "List projects in organization")
    public List<ProjectDtos.ProjectResponse> list(@PathVariable UUID orgId) {
        return projectService.list(orgId);
    }

    @GetMapping(value = "/projects/{projectId}", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Get project")
    public ProjectDtos.ProjectResponse get(@PathVariable UUID orgId, @PathVariable UUID projectId) {
        return projectService.get(orgId, projectId);
    }

    @PutMapping(value = "/projects/{projectId}", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Update project")
    public ProjectDtos.ProjectResponse update(
            @PathVariable UUID orgId,
            @PathVariable UUID projectId,
            @Valid @RequestBody ProjectDtos.UpdateProjectRequest request) {
        return projectService.update(orgId, projectId, request);
    }

    @DeleteMapping("/projects/{projectId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Delete project")
    public void delete(@PathVariable UUID orgId, @PathVariable UUID projectId) {
        projectService.delete(orgId, projectId);
    }

    @PutMapping(value = "/projects/{projectId}/repository", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Connect or update GitHub/git repository configuration")
    public ProjectDtos.RepositoryResponse upsertRepository(
            @PathVariable UUID orgId,
            @PathVariable UUID projectId,
            @Valid @RequestBody ProjectDtos.UpsertRepositoryRequest request) {
        return projectService.upsertRepository(orgId, projectId, request);
    }

    @GetMapping(value = "/projects/{projectId}/repository", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Get repository configuration (no raw credentials)")
    public ProjectDtos.RepositoryResponse getRepository(@PathVariable UUID orgId, @PathVariable UUID projectId) {
        return projectService.getRepository(orgId, projectId);
    }

    @PostMapping(value = "/projects/{projectId}/repository/validate", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Validate repository accessibility via GitHub API when token available")
    public ProjectDtos.RepositoryResponse validateRepository(@PathVariable UUID orgId, @PathVariable UUID projectId) {
        return projectService.validateRepository(orgId, projectId);
    }

    @PostMapping(value = "/projects/{projectId}/snapshots", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Pin a repo snapshot (metadata; clone/archive deferred)")
    public ProjectDtos.SnapshotResponse createSnapshot(
            @PathVariable UUID orgId,
            @PathVariable UUID projectId,
            @RequestBody(required = false) ProjectDtos.CreateSnapshotRequest request) {
        return projectService.createSnapshot(
                orgId,
                projectId,
                request == null ? new ProjectDtos.CreateSnapshotRequest(null, null, null, null, null) : request
        );
    }

    @GetMapping(value = "/projects/{projectId}/snapshots", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "List repo snapshots for a project")
    public List<ProjectDtos.SnapshotResponse> listSnapshots(@PathVariable UUID orgId, @PathVariable UUID projectId) {
        return projectService.listSnapshots(orgId, projectId);
    }

    @GetMapping(value = "/snapshots/{snapshotId}", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Get snapshot metadata")
    public ProjectDtos.SnapshotResponse getSnapshot(@PathVariable UUID orgId, @PathVariable UUID snapshotId) {
        return projectService.getSnapshot(orgId, snapshotId);
    }
}
