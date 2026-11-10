package com.agentbench.execution.runner;

import com.agentbench.evaluation.domain.FailureCode;
import com.agentbench.evaluation.domain.TestCaseStatus;
import com.agentbench.execution.config.ExecutionProperties;
import com.agentbench.execution.domain.IsolationResult;
import com.agentbench.execution.service.ExecutionIsolatorService;
import com.agentbench.execution.service.FixtureSuiteRunner;
import com.agentbench.execution.service.JUnitXmlParser;
import com.agentbench.platform.error.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Shared isolator + JUnit XML ingest path for Maven/Surefire, pytest, and Jest
 * (all emit or are configured to emit JUnit-compatible XML under {@code reportsDir}).
 */
public abstract class AbstractJUnitXmlToolchainRunner implements SuiteToolchainRunner {

    private static final Logger log = LoggerFactory.getLogger(AbstractJUnitXmlToolchainRunner.class);

    protected final ExecutionIsolatorService isolatorService;
    protected final ExecutionProperties properties;
    protected final ObjectMapper objectMapper;

    protected AbstractJUnitXmlToolchainRunner(
            ExecutionIsolatorService isolatorService,
            ExecutionProperties properties,
            ObjectMapper objectMapper) {
        this.isolatorService = isolatorService;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    public final List<FixtureSuiteRunner.CaseOutcome> run(SuiteExecutionRequest request) {
        Path reportsDir = resolveReportsDir(request.workspace(), request.suiteNode());
        if (reportsDir != null && Files.isDirectory(reportsDir)) {
            List<FixtureSuiteRunner.CaseOutcome> existing = JUnitXmlParser.parseDirectory(reportsDir);
            if (!existing.isEmpty()) {
                return existing;
            }
        }

        if (!request.canInvokeIsolator()) {
            // Offline / legacy callers: no isolator context — leave SuiteExecutionService to fall through.
            return List.of();
        }

        // Preserve fixture-v1 / phase-XML demos: blank entryCommand + declarative cases → defer.
        if (shouldDeferToFixture(request)) {
            return List.of();
        }

        try {
            maybeOverlayHiddenWorkspace(request);
            ensureReportsParent(request.workspace(), reportsDir);
            List<String> command = CommandArgv.fromEntryOrDefault(request.entryCommand(), defaultCommand(request));
            Map<String, String> env = new LinkedHashMap<>(defaultEnv(request));
            String image = resolveImage(request);

            IsolationResult isolation = isolatorService.runStage(
                    request.evaluationId(),
                    request.stage(),
                    request.workspace(),
                    request.hiddenFixturesDir(),
                    command,
                    request.memoryMb(),
                    request.cpuLimit(),
                    request.timeoutSec(),
                    request.networkEnabled(),
                    image,
                    env
            );

            List<FixtureSuiteRunner.CaseOutcome> fromReports = reportsDir == null
                    ? List.of()
                    : JUnitXmlParser.parseDirectory(reportsDir);
            if (!fromReports.isEmpty()) {
                return fromReports;
            }

            // No XML produced — surface a single runner ERROR so scoring still sees a result.
            String detail = summarizeIsolationFailure(isolation);
            log.warn("Toolchain {} produced no JUnit XML under {}: {}", id(),
                    reportsDir == null ? "(none)" : reportsDir.getFileName(), detail);
            return List.of(new FixtureSuiteRunner.CaseOutcome(
                    id() + ".runner",
                    isolation.timedOut() ? TestCaseStatus.ERROR : TestCaseStatus.ERROR,
                    1,
                    detail
            ));
        } catch (ApiException ex) {
            throw ex;
        } catch (IllegalArgumentException ex) {
            throw new ApiException(HttpStatus.BAD_REQUEST, FailureCode.RUNNER_ERROR.name(),
                    sanitize(ex.getMessage()));
        } catch (Exception ex) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, FailureCode.RUNNER_ERROR.name(),
                    sanitize(ex.getMessage()));
        }
    }

    protected abstract List<String> defaultCommand(SuiteExecutionRequest request);

    protected abstract String defaultReportsDir();

    protected abstract String resolveImage(SuiteExecutionRequest request);

    protected Map<String, String> defaultEnv(SuiteExecutionRequest request) {
        Map<String, String> env = new LinkedHashMap<>();
        env.put("HOME", "/tmp");
        env.put("TMPDIR", "/tmp");
        env.put("AGENTBENCH_TOOLCHAIN", id());
        env.put("AGENTBENCH_PHASE", request.phase().name());
        return env;
    }

    /**
     * When {@code entryCommand} is blank and the suite still carries fixture-v1 declarative
     * cases (or legacy phase XML fields), do not spawn the isolator — let
     * {@link com.agentbench.execution.service.SuiteExecutionService} fall through.
     * Pytest/Jest override to always execute when framework matches (default command).
     */
    protected boolean shouldDeferToFixture(SuiteExecutionRequest request) {
        if (request.entryCommand() != null && !request.entryCommand().isBlank()) {
            return false;
        }
        JsonNode fixture = parseFixture(request.suiteNode());
        if (fixture.has("cases") && fixture.get("cases").isArray() && !fixture.get("cases").isEmpty()) {
            return true;
        }
        if (fixture.has("expectedOutcomes") && fixture.get("expectedOutcomes").isObject()
                && !fixture.get("expectedOutcomes").isEmpty()) {
            return true;
        }
        if (fixture.hasNonNull("junitBaseline") || fixture.hasNonNull("junitPost")) {
            return true;
        }
        return false;
    }

    protected Path resolveReportsDir(Path workspace, JsonNode suiteNode) {
        JsonNode fixture = parseFixture(suiteNode);
        String relative = firstNonBlank(
                textOrNull(suiteNode, "reportsDir"),
                textOrNull(fixture, "reportsDir"),
                defaultReportsDir()
        );
        if (relative == null || relative.isBlank() || relative.contains("..") || relative.startsWith("/")) {
            return null;
        }
        Path resolved = workspace.resolve(relative).normalize();
        if (!resolved.startsWith(workspace.toAbsolutePath().normalize())) {
            return null;
        }
        return resolved;
    }

    /**
     * For HIDDEN_TESTS, merge optional {@code fixtureContent.workspace} files into the
     * hidden-run clone so pytest/jest can see tests that were never agent-visible.
     */
    protected void maybeOverlayHiddenWorkspace(SuiteExecutionRequest request) throws Exception {
        if (request.stage() != com.agentbench.execution.domain.ExecutionStage.HIDDEN_TESTS) {
            return;
        }
        JsonNode fixture = parseFixture(request.suiteNode());
        JsonNode workspaceFiles = fixture.path("workspace");
        if (!workspaceFiles.isObject() || workspaceFiles.isEmpty()) {
            return;
        }
        writeTree(request.workspace(), workspaceFiles);
    }

    private void writeTree(Path root, JsonNode files) throws Exception {
        Iterator<Map.Entry<String, JsonNode>> fields = files.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            String relative = entry.getKey();
            if (relative.contains("..") || relative.startsWith("/") || relative.startsWith("\\")) {
                throw new ApiException(HttpStatus.BAD_REQUEST, FailureCode.WORKSPACE_ERROR.name(),
                        "Illegal workspace path in hidden fixture");
            }
            Path target = root.resolve(relative).normalize();
            if (!target.startsWith(root.toAbsolutePath().normalize())) {
                throw new ApiException(HttpStatus.BAD_REQUEST, FailureCode.WORKSPACE_ERROR.name(),
                        "Hidden fixture path escaped workspace");
            }
            Files.createDirectories(target.getParent() == null ? root : target.getParent());
            String content = entry.getValue().isTextual()
                    ? entry.getValue().asText()
                    : entry.getValue().toString();
            Files.writeString(target, content, StandardCharsets.UTF_8);
        }
    }

    private JsonNode parseFixture(JsonNode suiteNode) {
        if (suiteNode == null) {
            return objectMapper.createObjectNode();
        }
        if (suiteNode.has("workspace") || suiteNode.has("reportsDir") || suiteNode.has("cases")) {
            return suiteNode;
        }
        String content = suiteNode.path("fixtureContent").asText("");
        if (content != null && content.trim().startsWith("{")) {
            try {
                return objectMapper.readTree(content);
            } catch (Exception ignored) {
                return objectMapper.createObjectNode();
            }
        }
        return objectMapper.createObjectNode();
    }

    private static void ensureReportsParent(Path workspace, Path reportsDir) throws Exception {
        if (reportsDir == null) {
            return;
        }
        Path parent = reportsDir;
        // Create the reports directory itself so tools can write into it under read-only rootfs.
        if (!Files.exists(parent)) {
            Files.createDirectories(parent);
        }
        // touch workspace so compiler is happy about unused when reportsDir null-checked above
        if (!Files.isDirectory(workspace)) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, FailureCode.WORKSPACE_ERROR.name(),
                    "Workspace missing");
        }
    }

    private static String summarizeIsolationFailure(IsolationResult isolation) {
        if (isolation == null) {
            return "isolator returned no result";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("exit=").append(isolation.exitCode());
        if (isolation.timedOut()) {
            sb.append(" timedOut=true");
        }
        if (isolation.killed()) {
            sb.append(" killed=true");
        }
        String err = isolation.stderr() == null ? "" : isolation.stderr().trim();
        String out = isolation.stdout() == null ? "" : isolation.stdout().trim();
        String detail = !err.isEmpty() ? err : out;
        if (!detail.isEmpty()) {
            sb.append(' ').append(sanitize(detail));
        }
        return sb.toString();
    }

    protected static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    protected static String textOrNull(JsonNode node, String field) {
        if (node == null || !node.hasNonNull(field)) {
            return null;
        }
        String value = node.get(field).asText(null);
        return value == null || value.isBlank() ? null : value;
    }

    protected static boolean frameworkMatches(String framework, String... tokens) {
        if (framework == null || framework.isBlank()) {
            return false;
        }
        String normalized = framework.toLowerCase(Locale.ROOT);
        for (String token : tokens) {
            if (normalized.equals(token) || normalized.contains(token)) {
                return true;
            }
        }
        return false;
    }

    private static String sanitize(String message) {
        if (message == null) {
            return "runner error";
        }
        String cleaned = message.replaceAll("/[A-Za-z0-9_./-]+", "[path]");
        return cleaned.substring(0, Math.min(cleaned.length(), 400));
    }
}
