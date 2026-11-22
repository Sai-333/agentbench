package com.agentbench.execution.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * REG-PREPARE-FILES: when a task has no git pin, {@link WorkspaceProvisioner} materializes
 * visible {@code workspaceFiles} under {@code workspaces/{org}/{evaluation}} and keeps
 * hidden fixtures under a separate {@code hidden-fixtures/} tree that is never present
 * in the agent-visible workspace. Git-pin checkout is covered by {@link GitSnapshotCheckoutTest}.
 */
class WorkspaceProvisionerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path artifactsRoot;

    private WorkspaceProvisioner provisioner;
    private UUID orgId;
    private UUID evaluationId;

    @BeforeEach
    void setUp() {
        provisioner = new WorkspaceProvisioner(
                artifactsRoot.toString(),
                MAPPER,
                new GitSnapshotCheckout(""));
        orgId = UUID.randomUUID();
        evaluationId = UUID.randomUUID();
    }

    @Test
    void provisionWithoutGitPinWritesVisibleWorkspaceFilesOutsideHiddenRoot() throws Exception {
        ObjectNode task = MAPPER.createObjectNode();
        ObjectNode fixtureWorkspace = task.putObject("fixtureWorkspace");
        fixtureWorkspace.put("src/App.java", "class App {}\n");
        fixtureWorkspace.put("README.md", "visible tree\n");

        ObjectNode suites = task.putArray("suites").addObject();
        suites.put("visibility", "HIDDEN");
        ObjectNode hiddenWorkspace = suites.putObject("fixtureWorkspace");
        hiddenWorkspace.put("secret/hidden.txt", "must-not-leak-into-agent-tree\n");
        suites.put("fixtureContent", "{\"cases\":[{\"id\":\"hidden.x\"}]}");

        Path workspace = provisioner.provision(orgId, evaluationId, task);

        Path expectedWorkspace = artifactsRoot.resolve("workspaces")
                .resolve(orgId.toString())
                .resolve(evaluationId.toString())
                .toAbsolutePath()
                .normalize();
        assertEquals(expectedWorkspace, workspace.toAbsolutePath().normalize());
        assertTrue(workspace.startsWith(artifactsRoot.resolve("workspaces").toAbsolutePath().normalize()));
        assertFalse(workspace.startsWith(artifactsRoot.resolve("hidden-fixtures").toAbsolutePath().normalize()));

        assertEquals("class App {}\n", Files.readString(workspace.resolve("src/App.java")));
        assertEquals("visible tree\n", Files.readString(workspace.resolve("README.md")));
        assertFalse(Files.exists(workspace.resolve("secret/hidden.txt")));
        assertFalse(treeContains(workspace, "must-not-leak-into-agent-tree"));
        assertFalse(Files.exists(artifactsRoot.resolve("hidden-fixtures")
                .resolve(orgId.toString())
                .resolve(evaluationId.toString())));
    }

    @Test
    void visibleSuiteWorkspaceIsUsedWhenTaskHasNoTopLevelFixture() throws Exception {
        ObjectNode task = MAPPER.createObjectNode();
        var suites = task.putArray("suites");

        ObjectNode hidden = suites.addObject();
        hidden.put("visibility", "HIDDEN");
        hidden.putObject("fixtureWorkspace").put("hidden/only.txt", "hidden-only\n");

        ObjectNode visible = suites.addObject();
        visible.put("visibility", "VISIBLE");
        visible.putObject("fixtureWorkspace").put("src/Visible.java", "class Visible {}\n");

        Path workspace = provisioner.provision(orgId, evaluationId, task);

        assertEquals("class Visible {}\n", Files.readString(workspace.resolve("src/Visible.java")));
        assertFalse(Files.exists(workspace.resolve("hidden/only.txt")));
        assertFalse(treeContains(workspace, "hidden-only"));
    }

    @Test
    void stageHiddenFixturesUsesSeparateRootAndLeavesAgentWorkspaceUntouched() throws Exception {
        ObjectNode task = MAPPER.createObjectNode();
        task.putObject("fixtureWorkspace").put("src/App.java", "class App {}\n");

        Path workspace = provisioner.provision(orgId, evaluationId, task);
        String hiddenBody = "{\"cases\":[{\"id\":\"hidden.secret\",\"assert\":\"never-in-agent-tree\"}]}";
        Path hiddenDir = provisioner.stageHiddenFixtures(orgId, evaluationId, hiddenBody);

        Path expectedHidden = artifactsRoot.resolve("hidden-fixtures")
                .resolve(orgId.toString())
                .resolve(evaluationId.toString())
                .toAbsolutePath()
                .normalize();
        assertEquals(expectedHidden, hiddenDir.toAbsolutePath().normalize());
        assertTrue(hiddenDir.startsWith(artifactsRoot.resolve("hidden-fixtures").toAbsolutePath().normalize()));
        assertFalse(hiddenDir.startsWith(artifactsRoot.resolve("workspaces").toAbsolutePath().normalize()));

        assertEquals(hiddenBody, Files.readString(hiddenDir.resolve("suite.json"), StandardCharsets.UTF_8));
        assertFalse(Files.exists(workspace.resolve("suite.json")));
        assertFalse(Files.exists(workspace.resolve("hidden-fixtures")));
        assertFalse(treeContains(workspace, "never-in-agent-tree"));
        assertEquals("class App {}\n", Files.readString(workspace.resolve("src/App.java")));
    }

    @Test
    void cleanupHiddenStagingRemovesHiddenTreesOnly() throws Exception {
        ObjectNode task = MAPPER.createObjectNode();
        task.putObject("fixtureWorkspace").put("keep.txt", "baseline\n");
        Path workspace = provisioner.provision(orgId, evaluationId, task);
        provisioner.stageHiddenFixtures(orgId, evaluationId, "{\"secret\":true}");
        Path hiddenRun = provisioner.cleanClone(workspace, orgId, evaluationId, "hidden-run");
        Files.writeString(hiddenRun.resolve("ephemeral.txt"), "hidden-run-only\n");

        provisioner.cleanupHiddenStaging(orgId, evaluationId);

        assertTrue(Files.exists(workspace.resolve("keep.txt")));
        assertFalse(Files.exists(artifactsRoot.resolve("hidden-fixtures")
                .resolve(orgId.toString())
                .resolve(evaluationId.toString())));
        assertFalse(Files.exists(hiddenRun));
    }

    private static boolean treeContains(Path root, String needle) throws Exception {
        if (!Files.exists(root)) {
            return false;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile).anyMatch(path -> {
                try {
                    return Files.readString(path).contains(needle);
                } catch (Exception ex) {
                    throw new IllegalStateException(ex);
                }
            });
        }
    }
}
