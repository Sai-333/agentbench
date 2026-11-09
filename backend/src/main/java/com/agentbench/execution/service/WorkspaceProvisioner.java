package com.agentbench.execution.service;

import com.agentbench.evaluation.domain.FailureCode;
import com.agentbench.platform.error.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * Provisions an isolated evaluation workspace. Full Docker isolation lands in M6/M7;
 * M4 uses filesystem isolation under the artifacts root.
 */
@Service
public class WorkspaceProvisioner {

    private final Path workspacesRoot;
    private final ObjectMapper objectMapper;

    public WorkspaceProvisioner(
            @Value("${agentbench.artifacts.root}") String artifactsRoot,
            ObjectMapper objectMapper) {
        this.workspacesRoot = Path.of(artifactsRoot).resolve("workspaces").toAbsolutePath().normalize();
        this.objectMapper = objectMapper;
    }

    public Path provision(UUID orgId, UUID evaluationId, JsonNode snapshotTaskNode) {
        try {
            Files.createDirectories(workspacesRoot);
            Path workspace = workspacesRoot.resolve(orgId.toString()).resolve(evaluationId.toString());
            if (Files.exists(workspace)) {
                return workspace;
            }
            Files.createDirectories(workspace);
            JsonNode workspaceFiles = resolveWorkspaceFiles(snapshotTaskNode);
            if (workspaceFiles != null && workspaceFiles.isObject() && !workspaceFiles.isEmpty()) {
                writeTree(workspace, workspaceFiles);
            } else {
                // Minimal placeholder so patch apply / checks have a root.
                Files.writeString(workspace.resolve("README.md"), "AgentBench evaluation workspace\n", StandardCharsets.UTF_8);
            }
            return workspace;
        } catch (IOException ex) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, FailureCode.WORKSPACE_ERROR.name(),
                    "Failed to provision evaluation workspace");
        }
    }

    public Path cleanClone(Path sourceWorkspace, UUID orgId, UUID evaluationId, String suffix) {
        try {
            Path clone = workspacesRoot.resolve(orgId.toString()).resolve(evaluationId + "-" + suffix);
            if (Files.exists(clone)) {
                deleteRecursive(clone);
            }
            copyRecursive(sourceWorkspace, clone);
            return clone;
        } catch (IOException ex) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, FailureCode.WORKSPACE_ERROR.name(),
                    "Failed to clone evaluation workspace");
        }
    }

    public Path requireExisting(String workspaceUri) {
        if (workspaceUri == null || workspaceUri.isBlank()) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, FailureCode.WORKSPACE_ERROR.name(),
                    "Workspace URI missing");
        }
        Path path = Path.of(workspaceUri).toAbsolutePath().normalize();
        if (!path.startsWith(workspacesRoot) || !Files.isDirectory(path)) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, FailureCode.WORKSPACE_ERROR.name(),
                    "Invalid workspace path");
        }
        return path;
    }

    private JsonNode resolveWorkspaceFiles(JsonNode snapshotTaskNode) {
        JsonNode direct = snapshotTaskNode.path("fixtureWorkspace");
        if (direct.isObject() && !direct.isEmpty()) {
            return direct;
        }
        JsonNode suites = snapshotTaskNode.path("suites");
        if (suites.isArray()) {
            for (JsonNode suite : suites) {
                if (!"VISIBLE".equalsIgnoreCase(suite.path("visibility").asText())) {
                    continue;
                }
                JsonNode embedded = suite.path("fixtureWorkspace");
                if (embedded.isObject() && !embedded.isEmpty()) {
                    return embedded;
                }
                String content = suite.path("fixtureContent").asText("");
                if (content.trim().startsWith("{")) {
                    try {
                        JsonNode parsed = objectMapper.readTree(content);
                        JsonNode workspace = parsed.path("workspace");
                        if (workspace.isObject() && !workspace.isEmpty()) {
                            return workspace;
                        }
                    } catch (Exception ignored) {
                        // non-JSON fixture content
                    }
                }
            }
        }
        return null;
    }

    private void writeTree(Path root, JsonNode files) throws IOException {
        Iterator<Map.Entry<String, JsonNode>> fields = files.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            String relative = entry.getKey();
            if (relative.contains("..") || relative.startsWith("/") || relative.startsWith("\\")) {
                throw new ApiException(HttpStatus.BAD_REQUEST, FailureCode.WORKSPACE_ERROR.name(),
                        "Illegal workspace path in fixture");
            }
            Path target = root.resolve(relative).normalize();
            if (!target.startsWith(root)) {
                throw new ApiException(HttpStatus.BAD_REQUEST, FailureCode.WORKSPACE_ERROR.name(),
                        "Workspace path escaped root");
            }
            Files.createDirectories(target.getParent() == null ? root : target.getParent());
            Files.writeString(target, entry.getValue().asText(""), StandardCharsets.UTF_8);
        }
    }

    private static void copyRecursive(Path source, Path target) throws IOException {
        Files.walk(source).forEach(path -> {
            try {
                Path relative = source.relativize(path);
                Path dest = target.resolve(relative.toString());
                if (Files.isDirectory(path)) {
                    Files.createDirectories(dest);
                } else {
                    Files.createDirectories(dest.getParent());
                    Files.copy(path, dest);
                }
            } catch (IOException ex) {
                throw new IllegalStateException(ex);
            }
        });
    }

    private static void deleteRecursive(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (var walk = Files.walk(root)) {
            walk.sorted((a, b) -> b.compareTo(a)).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ex) {
                    throw new IllegalStateException(ex);
                }
            });
        }
    }

    public ObjectMapper objectMapper() {
        return objectMapper;
    }
}
