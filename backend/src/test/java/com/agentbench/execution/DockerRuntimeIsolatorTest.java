package com.agentbench.execution;

import com.agentbench.execution.config.ExecutionProperties;
import com.agentbench.execution.domain.ExecutionStage;
import com.agentbench.execution.domain.IsolationSpec;
import com.agentbench.execution.service.DockerRuntimeIsolator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DockerRuntimeIsolatorTest {

    @TempDir
    Path temp;

    @Test
    void buildCommandIncludesHardeningFlagsAndNeverDockerSock() {
        ExecutionProperties props = new ExecutionProperties();
        props.setImage("eclipse-temurin:21-jre-alpine");
        props.setImageDigest("deadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeef");
        DockerRuntimeIsolator isolator = new DockerRuntimeIsolator(props);

        IsolationSpec spec = new IsolationSpec(
                "ab-test-visible_tests",
                ExecutionStage.VISIBLE_TESTS,
                temp,
                null,
                List.of("echo", "ok"),
                Duration.ofSeconds(30),
                256,
                0.5,
                64,
                false,
                true,
                Map.of()
        );
        List<String> cmd = isolator.buildDockerCommand(spec);
        String joined = String.join(" ", cmd);
        assertTrue(joined.contains("--network none"));
        assertTrue(joined.contains("--cap-drop ALL"));
        assertTrue(joined.contains("--security-opt no-new-privileges:true"));
        assertTrue(joined.contains("--read-only"));
        assertTrue(joined.contains("--memory 256m"));
        assertTrue(joined.contains("--pids-limit 64"));
        assertTrue(joined.contains("--user 65534:65534"));
        assertTrue(joined.contains("@sha256:deadbeef"));
        assertFalse(joined.contains("docker.sock"));
        assertFalse(joined.contains("--privileged"));
    }

    @Test
    void rejectsHiddenFixturesOutsideHiddenStage() {
        IsolationSpec mismatched = new IsolationSpec(
                "bad",
                ExecutionStage.VISIBLE_TESTS,
                temp,
                temp.resolve("hidden"),
                List.of("true"),
                Duration.ofSeconds(5),
                128,
                0.5,
                32,
                false,
                true,
                Map.of()
        );
        assertThrows(IllegalArgumentException.class, () -> DockerRuntimeIsolator.assertSafeSpec(mismatched));
    }

    @Test
    void rejectsPrivilegedCommandFragments() {
        IsolationSpec spec = new IsolationSpec(
                "evil",
                ExecutionStage.AGENT,
                temp,
                null,
                List.of("sh", "-c", "mount /var/run/docker.sock"),
                Duration.ofSeconds(5),
                128,
                0.5,
                32,
                false,
                true,
                Map.of()
        );
        assertThrows(IllegalArgumentException.class, () -> DockerRuntimeIsolator.assertSafeSpec(spec));
    }
}
