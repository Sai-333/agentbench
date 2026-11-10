package com.agentbench.agents.infra;

import com.agentbench.agents.domain.AdapterType;
import com.agentbench.agents.domain.spi.AgentInvocationRequest;
import com.agentbench.agents.domain.spi.AgentInvocationResult;
import com.agentbench.agents.domain.spi.AgentInvocationStatus;
import com.agentbench.agents.domain.spi.AgentProvider;
import com.agentbench.agents.domain.spi.CodingAgentCliAdapter;
import com.agentbench.execution.domain.ExecutionStage;
import com.agentbench.execution.domain.IsolationResult;
import com.agentbench.execution.service.ExecutionIsolatorService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * CODING_CLI provider — invokes an external coding-agent CLI inside {@link ExecutionIsolatorService}
 * (Docker / gVisor / local fallback). Never shells out on the host outside the isolator.
 *
 * <p>Config keys (agent {@code config} JSON):
 * <ul>
 *   <li>{@code patch} — deterministic fixture escape hatch (no process spawn; CI demos)</li>
 *   <li>{@code argv} — preferred JSON string array of command + args (placeholders expanded)</li>
 *   <li>{@code binary} / {@code command} — executable name (default from
 *       {@code agentbench.agents.coding-cli.default-binary})</li>
 *   <li>{@code args} — JSON string array appended after binary</li>
 *   <li>{@code commandTemplate} — single string with placeholders; split on whitespace into argv</li>
 *   <li>{@code image} — optional container image override for the isolator run</li>
 *   <li>{@code patchFile} — workspace-relative path where the CLI writes a unified diff
 *       (default {@code .agentbench/patch.diff})</li>
 *   <li>{@code networkEnabled}, {@code memoryMb}, {@code cpuLimit}, {@code timeoutSec}, {@code model}</li>
 * </ul>
 *
 * <p>Placeholders: {@code {{prompt}}}, {@code {{promptFile}}}, {@code {{contextFile}}},
 * {@code {{patchFile}}}, {@code {{workspace}}}, {@code {{evaluationId}}}, {@code {{taskId}}},
 * {@code {{attempt}}}, {@code {{orgId}}}.
 *
 * <p><strong>Residual risk:</strong> isolation strength equals the active {@code RuntimeIsolator}
 * (Docker shared-kernel residual; prefer gVisor on Linux). Not a certified hostile multi-tenant claim.
 */
@Component
public class CodingCliAgentProvider implements CodingAgentCliAdapter, AgentProvider {

    public static final String PROVIDER_VERSION = "coding-cli-v1";
    public static final String DEFAULT_PATCH_FILE = ".agentbench/patch.diff";
    public static final String PROMPT_RELATIVE = ".agentbench/prompt.txt";
    public static final String CONTEXT_RELATIVE = ".agentbench/context.json";

    private static final Logger log = LoggerFactory.getLogger(CodingCliAgentProvider.class);
    private static final Pattern DIFF_FENCE = Pattern.compile(
            "```(?:diff|patch)?\\s*([\\s\\S]*?)```", Pattern.CASE_INSENSITIVE);
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{([a-zA-Z0-9_]+)}}");

    private final ObjectMapper objectMapper;
    private final ExecutionIsolatorService isolatorService;
    private final String defaultBinary;
    private final String defaultPatchFile;
    private final int defaultTimeoutSec;
    private final boolean networkEnabledByDefault;

    public CodingCliAgentProvider(
            ObjectMapper objectMapper,
            ExecutionIsolatorService isolatorService,
            @Value("${agentbench.agents.coding-cli.default-binary:coding-agent}") String defaultBinary,
            @Value("${agentbench.agents.coding-cli.default-patch-file:.agentbench/patch.diff}") String defaultPatchFile,
            @Value("${agentbench.agents.coding-cli.default-timeout-sec:60}") int defaultTimeoutSec,
            @Value("${agentbench.agents.coding-cli.network-enabled-by-default:false}") boolean networkEnabledByDefault) {
        this.objectMapper = objectMapper;
        this.isolatorService = isolatorService;
        this.defaultBinary = defaultBinary == null || defaultBinary.isBlank() ? "coding-agent" : defaultBinary.trim();
        this.defaultPatchFile = defaultPatchFile == null || defaultPatchFile.isBlank()
                ? DEFAULT_PATCH_FILE : defaultPatchFile.trim();
        this.defaultTimeoutSec = Math.max(5, defaultTimeoutSec);
        this.networkEnabledByDefault = networkEnabledByDefault;
    }

    /** Test constructor. */
    CodingCliAgentProvider(ObjectMapper objectMapper, ExecutionIsolatorService isolatorService) {
        this(objectMapper, isolatorService, "coding-agent", DEFAULT_PATCH_FILE, 60, false);
    }

    @Override
    public String adapterType() {
        return AdapterType.CODING_CLI.name();
    }

    @Override
    public AgentInvocationResult invoke(AgentInvocationRequest request) {
        JsonNode config = readConfig(request.agent().configJson());
        String commandLabel = resolveCommandLabel(config);
        String workingDirectory = request.workspacePath();
        return invokeCli(request, commandLabel, workingDirectory);
    }

    @Override
    public AgentInvocationResult invokeCli(
            AgentInvocationRequest request,
            String command,
            String workingDirectory) {
        JsonNode config = readConfig(request.agent().configJson());
        String model = textOrNull(config, "model");

        // Deterministic escape hatch for CI without spawning processes / needing a live CLI binary.
        if (config.hasNonNull("patch")) {
            return AgentInvocationResult.success(
                    config.get("patch").asText(),
                    model != null ? model : "cli-fixture",
                    PROVIDER_VERSION,
                    null, null, null, null,
                    Map.of(
                            "mode", "fixture-patch",
                            "command", command == null ? "" : command,
                            "isolation", "fixture-no-spawn",
                            "providerVersion", PROVIDER_VERSION
                    )
            );
        }

        Path workspace = resolveWorkspace(workingDirectory, request.workspacePath());
        if (workspace == null) {
            return AgentInvocationResult.failure(
                    AgentInvocationStatus.FAILED,
                    "CODING_CLI_WORKSPACE_REQUIRED",
                    "CODING_CLI requires an evaluation workspace (or config.patch fixture mode)",
                    model,
                    Map.of("command", command == null ? "" : command, "isolation", "none")
            );
        }

        String patchRelative = config.path("patchFile").asText(defaultPatchFile);
        if (patchRelative.contains("..") || patchRelative.startsWith("/") || patchRelative.startsWith("\\")) {
            return AgentInvocationResult.failure(
                    AgentInvocationStatus.FAILED,
                    "CODING_CLI_INVALID_CONFIG",
                    "patchFile must be a relative path without '..'",
                    model,
                    Map.of("patchFile", patchRelative)
            );
        }

        try {
            stageAgentInputs(workspace, request);
        } catch (Exception ex) {
            return AgentInvocationResult.failure(
                    AgentInvocationStatus.FAILED,
                    "CODING_CLI_WORKSPACE_IO",
                    SecretRedactor.sanitizeError(ex.getMessage()),
                    model,
                    Map.of("workspace", workspace.toString())
            );
        }

        List<String> argv;
        try {
            argv = resolveArgv(config, command, request, workspace, patchRelative);
        } catch (IllegalArgumentException ex) {
            return AgentInvocationResult.failure(
                    AgentInvocationStatus.FAILED,
                    "CODING_CLI_INVALID_CONFIG",
                    ex.getMessage(),
                    model,
                    Map.of()
            );
        }

        Duration timeout = request.timeout() != null && !request.timeout().isZero() && !request.timeout().isNegative()
                ? request.timeout()
                : Duration.ofSeconds(config.path("timeoutSec").asInt(defaultTimeoutSec));
        int timeoutSec = (int) Math.max(5, timeout.toSeconds());
        Integer memoryMb = config.has("memoryMb") ? config.get("memoryMb").asInt() : null;
        Double cpuLimit = config.has("cpuLimit") ? config.get("cpuLimit").asDouble() : null;
        boolean networkEnabled = config.path("networkEnabled").asBoolean(networkEnabledByDefault);
        String image = textOrNull(config, "image");
        if (image == null) {
            image = textOrNull(config, "imageOverride");
        }

        Map<String, String> env = new LinkedHashMap<>();
        env.put("AGENTBENCH_PROMPT_FILE", "/workspace/" + PROMPT_RELATIVE);
        env.put("AGENTBENCH_CONTEXT_FILE", "/workspace/" + CONTEXT_RELATIVE);
        env.put("AGENTBENCH_PATCH_FILE", "/workspace/" + patchRelative);
        env.put("AGENTBENCH_EVALUATION_ID", request.evaluationId() == null ? "" : request.evaluationId().toString());
        env.put("AGENTBENCH_TASK_ID", request.taskId() == null ? "" : request.taskId().toString());
        env.put("AGENTBENCH_ATTEMPT", String.valueOf(request.attempt()));
        if (model != null) {
            env.put("AGENTBENCH_MODEL", model);
        }

        UUID evaluationId = request.evaluationId() != null ? request.evaluationId() : UUID.randomUUID();
        IsolationResult isolation;
        try {
            log.info("CODING_CLI isolator invoke evaluation={} attempt={} argv0={} image={}",
                    evaluationId, request.attempt(), argv.isEmpty() ? "" : argv.get(0),
                    image == null ? "(default)" : image);
            isolation = isolatorService.runStage(
                    evaluationId,
                    ExecutionStage.AGENT,
                    workspace,
                    null,
                    argv,
                    memoryMb,
                    cpuLimit,
                    timeoutSec,
                    networkEnabled,
                    image,
                    env
            );
        } catch (IllegalStateException ex) {
            // e.g. gVisor mode fail-fast
            return AgentInvocationResult.failure(
                    AgentInvocationStatus.FAILED,
                    "CODING_CLI_ISOLATOR_UNAVAILABLE",
                    SecretRedactor.sanitizeError(ex.getMessage()),
                    model,
                    Map.of("isolation", "unavailable")
            );
        } catch (Exception ex) {
            return AgentInvocationResult.failure(
                    AgentInvocationStatus.FAILED,
                    "CODING_CLI_INVOKE_ERROR",
                    SecretRedactor.sanitizeError(ex.getMessage()),
                    model,
                    Map.of("isolation", "error")
            );
        }

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("mode", "isolator");
        meta.put("command", command == null ? "" : command);
        meta.put("argv", redactArgv(argv));
        meta.put("isolation", isolation.runtime());
        meta.put("exitCode", isolation.exitCode());
        meta.put("timedOut", isolation.timedOut());
        meta.put("killed", isolation.killed());
        meta.put("providerVersion", PROVIDER_VERSION);
        if (image != null) {
            meta.put("image", image);
        }
        if (isolation.meta() != null) {
            meta.put("isolatorMeta", isolation.meta());
        }
        String stderrSnippet = truncate(isolation.stderr(), 4_000);
        if (stderrSnippet != null && !stderrSnippet.isBlank()) {
            meta.put("stderrSnippet", stderrSnippet);
        }
        String stdoutSnippet = truncate(isolation.stdout(), 2_000);
        if (stdoutSnippet != null && !stdoutSnippet.isBlank()) {
            meta.put("stdoutSnippet", stdoutSnippet);
        }

        if (isolation.timedOut()) {
            return AgentInvocationResult.failure(
                    AgentInvocationStatus.TIMED_OUT,
                    "CODING_CLI_TIMEOUT",
                    "Coding CLI timed out after " + timeoutSec + "s inside " + isolation.runtime(),
                    model,
                    meta
            );
        }
        if (isolation.killed() && isolation.exitCode() != 0) {
            return AgentInvocationResult.failure(
                    AgentInvocationStatus.FAILED,
                    "CODING_CLI_KILLED",
                    "Coding CLI process was killed by the isolator",
                    model,
                    meta
            );
        }
        if (isolation.exitCode() != 0) {
            return AgentInvocationResult.failure(
                    AgentInvocationStatus.FAILED,
                    "CODING_CLI_EXIT_" + isolation.exitCode(),
                    "Coding CLI exited with code " + isolation.exitCode()
                            + (stderrSnippet == null || stderrSnippet.isBlank()
                            ? "" : ": " + stderrSnippet),
                    model,
                    meta
            );
        }

        String patch = readPatchArtifact(workspace, patchRelative);
        if (patch == null || patch.isBlank()) {
            patch = extractPatchFromStdout(isolation.stdout());
        }
        if (patch == null || patch.isBlank()) {
            return AgentInvocationResult.failure(
                    AgentInvocationStatus.FAILED,
                    "CODING_CLI_NO_PATCH",
                    "Coding CLI succeeded but produced no patch (expected file "
                            + patchRelative + " or unified diff on stdout)",
                    model,
                    meta
            );
        }

        meta.put("patchSource", Files.exists(workspace.resolve(patchRelative)) ? "patchFile" : "stdout");
        meta.put("patchBytes", patch.getBytes(StandardCharsets.UTF_8).length);
        return AgentInvocationResult.success(
                patch,
                model != null ? model : "coding-cli",
                PROVIDER_VERSION,
                null, null, null, null,
                meta
        );
    }

    static String extractPatchFromStdout(String stdout) {
        if (stdout == null || stdout.isBlank()) {
            return null;
        }
        Matcher fenced = DIFF_FENCE.matcher(stdout);
        if (fenced.find()) {
            String inner = fenced.group(1).trim();
            if (inner.contains("diff --git") || inner.startsWith("--- ")) {
                return inner;
            }
        }
        int idx = stdout.indexOf("diff --git ");
        if (idx >= 0) {
            return stdout.substring(idx).trim();
        }
        idx = stdout.indexOf("--- a/");
        if (idx >= 0) {
            return stdout.substring(idx).trim();
        }
        return null;
    }

    private void stageAgentInputs(Path workspace, AgentInvocationRequest request) throws Exception {
        Path agentbenchDir = workspace.resolve(".agentbench");
        Files.createDirectories(agentbenchDir);
        String prompt = request.taskPrompt() == null ? "" : request.taskPrompt();
        Files.writeString(workspace.resolve(PROMPT_RELATIVE), prompt, StandardCharsets.UTF_8);
        String context = request.visibleContextJson() == null || request.visibleContextJson().isBlank()
                ? "{}" : request.visibleContextJson();
        Files.writeString(workspace.resolve(CONTEXT_RELATIVE), context, StandardCharsets.UTF_8);
    }

    private List<String> resolveArgv(
            JsonNode config,
            String commandLabel,
            AgentInvocationRequest request,
            Path workspace,
            String patchRelative) {
        Map<String, String> placeholders = buildPlaceholders(request, workspace, patchRelative);

        if (config.has("argv") && config.get("argv").isArray()) {
            List<String> out = new ArrayList<>();
            for (JsonNode node : config.get("argv")) {
                out.add(expand(node.asText(""), placeholders));
            }
            if (out.isEmpty()) {
                throw new IllegalArgumentException("argv must not be empty");
            }
            assertSafeArgv(out);
            return out;
        }

        if (config.hasNonNull("commandTemplate")) {
            String template = expand(config.get("commandTemplate").asText(), placeholders);
            List<String> out = splitTemplate(template);
            if (out.isEmpty()) {
                throw new IllegalArgumentException("commandTemplate produced empty argv");
            }
            assertSafeArgv(out);
            return out;
        }

        String binary = firstNonBlank(
                textOrNull(config, "binary"),
                textOrNull(config, "command"),
                commandLabel,
                defaultBinary
        );
        binary = expand(binary, placeholders);
        List<String> out = new ArrayList<>();
        out.add(binary);
        if (config.has("args") && config.get("args").isArray()) {
            for (JsonNode node : config.get("args")) {
                out.add(expand(node.asText(""), placeholders));
            }
        }
        assertSafeArgv(out);
        return out;
    }

    private static Map<String, String> buildPlaceholders(
            AgentInvocationRequest request,
            Path workspace,
            String patchRelative) {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("prompt", request.taskPrompt() == null ? "" : request.taskPrompt());
        map.put("promptFile", "/workspace/" + PROMPT_RELATIVE);
        map.put("contextFile", "/workspace/" + CONTEXT_RELATIVE);
        map.put("patchFile", "/workspace/" + patchRelative);
        map.put("workspace", "/workspace");
        map.put("workspaceHost", workspace.toAbsolutePath().toString());
        map.put("evaluationId", request.evaluationId() == null ? "" : request.evaluationId().toString());
        map.put("taskId", request.taskId() == null ? "" : request.taskId().toString());
        map.put("orgId", request.orgId() == null ? "" : request.orgId().toString());
        map.put("attempt", String.valueOf(request.attempt()));
        return map;
    }

    private static String expand(String template, Map<String, String> placeholders) {
        if (template == null || template.isEmpty()) {
            return "";
        }
        Matcher matcher = PLACEHOLDER.matcher(template);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            String key = matcher.group(1);
            String value = placeholders.getOrDefault(key, "");
            matcher.appendReplacement(sb, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    private static List<String> splitTemplate(String template) {
        List<String> parts = new ArrayList<>();
        if (template == null || template.isBlank()) {
            return parts;
        }
        // Simple whitespace split — prefer argv JSON for args with spaces.
        for (String part : template.trim().split("\\s+")) {
            if (!part.isBlank()) {
                parts.add(part);
            }
        }
        return parts;
    }

    private static void assertSafeArgv(List<String> argv) {
        for (String part : argv) {
            String lower = part.toLowerCase(Locale.ROOT);
            if (lower.contains("docker.sock") || lower.contains("--privileged")) {
                throw new IllegalArgumentException("Rejected unsafe CODING_CLI argv fragment");
            }
        }
    }

    private static Path resolveWorkspace(String workingDirectory, String requestWorkspace) {
        String path = firstNonBlank(workingDirectory, requestWorkspace);
        if (path == null) {
            return null;
        }
        Path resolved = Path.of(path).toAbsolutePath().normalize();
        if (!Files.isDirectory(resolved)) {
            return null;
        }
        return resolved;
    }

    private static String readPatchArtifact(Path workspace, String patchRelative) {
        try {
            Path file = workspace.resolve(patchRelative).normalize();
            if (!file.startsWith(workspace.toAbsolutePath().normalize())) {
                return null;
            }
            if (!Files.isRegularFile(file)) {
                return null;
            }
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (Exception ex) {
            return null;
        }
    }

    private String resolveCommandLabel(JsonNode config) {
        if (config.has("argv") && config.get("argv").isArray() && !config.get("argv").isEmpty()) {
            return config.get("argv").get(0).asText(defaultBinary);
        }
        return firstNonBlank(
                textOrNull(config, "binary"),
                textOrNull(config, "command"),
                defaultBinary
        );
    }

    private JsonNode readConfig(String configJson) {
        try {
            return objectMapper.readTree(configJson == null || configJson.isBlank() ? "{}" : configJson);
        } catch (Exception ex) {
            return objectMapper.createObjectNode();
        }
    }

    private static String textOrNull(JsonNode config, String field) {
        if (config == null || !config.hasNonNull(field)) {
            return null;
        }
        String value = config.get(field).asText();
        return value == null || value.isBlank() ? null : value;
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static List<String> redactArgv(List<String> argv) {
        List<String> out = new ArrayList<>(argv.size());
        for (String part : argv) {
            out.add(SecretRedactor.redact(part));
        }
        return out;
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        if (value.length() <= max) {
            return value;
        }
        return value.substring(0, max) + "…[truncated]";
    }
}
