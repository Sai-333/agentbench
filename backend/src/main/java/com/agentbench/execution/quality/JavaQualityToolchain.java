package com.agentbench.execution.quality;

import com.agentbench.agents.infra.SecretRedactor;
import com.agentbench.execution.config.ExecutionProperties;
import com.agentbench.execution.domain.ExecutionStage;
import com.agentbench.execution.domain.IsolationResult;
import com.agentbench.execution.service.ExecutionIsolatorService;
import com.puppycrawl.tools.checkstyle.Checker;
import com.puppycrawl.tools.checkstyle.ConfigurationLoader;
import com.puppycrawl.tools.checkstyle.PropertiesExpander;
import com.puppycrawl.tools.checkstyle.api.AuditEvent;
import com.puppycrawl.tools.checkstyle.api.AuditListener;
import com.puppycrawl.tools.checkstyle.api.Configuration;
import edu.umd.cs.findbugs.BugCollectionBugReporter;
import edu.umd.cs.findbugs.BugInstance;
import edu.umd.cs.findbugs.DetectorFactoryCollection;
import edu.umd.cs.findbugs.FindBugs2;
import edu.umd.cs.findbugs.Priorities;
import edu.umd.cs.findbugs.Project;
import edu.umd.cs.findbugs.BugCollection;
import edu.umd.cs.findbugs.SourceLineAnnotation;
import edu.umd.cs.findbugs.config.UserPreferences;
import net.sourceforge.pmd.PMDConfiguration;
import net.sourceforge.pmd.PmdAnalysis;
import net.sourceforge.pmd.lang.LanguageRegistry;
import net.sourceforge.pmd.reporting.Report;
import net.sourceforge.pmd.reporting.RuleViolation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.tools.JavaCompiler;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.UUID;

/**
 * Runs Checkstyle, PMD, and SpotBugs on a workspace. Findings come from those tools.
 * A tool that cannot run is a stub with notes {@code did_not_run}.
 */
public final class JavaQualityToolchain {

    public static final String DID_NOT_RUN = "did_not_run";
    private static final Logger log = LoggerFactory.getLogger(JavaQualityToolchain.class);
    private static final List<String> PMD_RULESETS = List.of(
            "category/java/errorprone.xml",
            "category/java/bestpractices.xml",
            "category/java/design.xml",
            "category/java/codestyle.xml"
    );
    private static final int MAVEN_COMPILE_TIMEOUT_SEC = 600;
    private static final int MAVEN_COMPILE_MEMORY_MB = 2048;

    private final ExecutionIsolatorService isolatorService;
    private final ExecutionProperties executionProperties;
    private final List<QualityToolReport> fixedReports;
    private final boolean enginesEnabled;

    private JavaQualityToolchain(
            ExecutionIsolatorService isolatorService,
            ExecutionProperties executionProperties,
            List<QualityToolReport> fixedReports,
            boolean enginesEnabled) {
        this.isolatorService = isolatorService;
        this.executionProperties = executionProperties;
        this.fixedReports = fixedReports;
        this.enginesEnabled = enginesEnabled;
    }

    /** In-process engines. SpotBugs compiles JDK sources when no class files exist. */
    public static JavaQualityToolchain inProcess() {
        return new JavaQualityToolchain(null, null, null, true);
    }

    /**
     * In-process engines. A Maven tree with no class files is compiled through the
     * Java runner image so SpotBugs can read bytecode.
     */
    public static JavaQualityToolchain withIsolator(
            ExecutionIsolatorService isolatorService,
            ExecutionProperties executionProperties) {
        return new JavaQualityToolchain(isolatorService, executionProperties, null, true);
    }

    /** Engines are absent. Every primary tool reports that it did not run. */
    public static JavaQualityToolchain unavailable() {
        return new JavaQualityToolchain(null, null, null, false);
    }

    /** Test double that returns the given primary-tool reports. */
    public static JavaQualityToolchain reporting(List<QualityToolReport> reports) {
        return new JavaQualityToolchain(null, null, List.copyOf(reports), true);
    }

    public List<QualityToolReport> scan(UUID evaluationId, Path workspace) {
        if (fixedReports != null) {
            return fixedReports;
        }
        if (!enginesEnabled || workspace == null || !Files.isDirectory(workspace)) {
            return List.of(didNotRun("checkstyle"), didNotRun("pmd"), didNotRun("spotbugs"));
        }
        return List.of(
                runCheckstyle(workspace),
                runPmd(workspace),
                runSpotBugs(evaluationId, workspace)
        );
    }

    private QualityToolReport runCheckstyle(Path workspace) {
        List<Path> sources = javaSources(workspace);
        if (sources.isEmpty()) {
            return didNotRun("checkstyle");
        }
        Checker checker = null;
        Path configFile = null;
        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        try {
            Thread.currentThread().setContextClassLoader(Checker.class.getClassLoader());
            checker = new Checker();
            checker.setModuleClassLoader(Checker.class.getClassLoader());
            configFile = materializeCheckstyleConfig();
            Configuration config = ConfigurationLoader.loadConfiguration(
                    configFile.toString(),
                    new PropertiesExpander(new Properties()),
                    ConfigurationLoader.IgnoredModulesOptions.EXECUTE);
            checker.configure(config);
            List<QualityFinding> findings = new ArrayList<>();
            checker.addListener(new AuditListener() {
                @Override
                public void auditStarted(AuditEvent event) {
                }

                @Override
                public void auditFinished(AuditEvent event) {
                }

                @Override
                public void fileStarted(AuditEvent event) {
                }

                @Override
                public void fileFinished(AuditEvent event) {
                }

                @Override
                public void addError(AuditEvent event) {
                    if (event == null || event.getSeverityLevel() == null) {
                        return;
                    }
                    String severity = event.getSeverityLevel().toString();
                    if ("IGNORE".equalsIgnoreCase(severity)) {
                        return;
                    }
                    String rule = event.getSourceName() == null || event.getSourceName().isBlank()
                            ? "checkstyle"
                            : event.getSourceName();
                    String path = relative(workspace, event.getFileName());
                    String message = event.getMessage() == null ? "" : event.getMessage().trim();
                    findings.add(finding("checkstyle", rule, severity, path, event.getLine(), message));
                }

                @Override
                public void addException(AuditEvent event, Throwable throwable) {
                    log.debug("Checkstyle file exception on {}", event == null ? "" : relative(workspace, event.getFileName()));
                }
            });
            List<java.io.File> files = sources.stream().map(Path::toFile).toList();
            checker.process(files);
            log.info("Checkstyle reported {} findings", findings.size());
            return new QualityToolReport("checkstyle", findings, false, "checkstyle");
        } catch (Exception ex) {
            log.info("Checkstyle did not run: {}", SecretRedactor.sanitizeError(ex.getMessage()));
            return didNotRun("checkstyle");
        } finally {
            Thread.currentThread().setContextClassLoader(previous);
            if (checker != null) {
                checker.destroy();
            }
            deleteQuietly(configFile);
        }
    }

    private QualityToolReport runPmd(Path workspace) {
        List<Path> sources = javaSources(workspace);
        if (sources.isEmpty()) {
            return didNotRun("pmd");
        }
        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        try {
            Thread.currentThread().setContextClassLoader(PmdAnalysis.class.getClassLoader());
            PMDConfiguration configuration = new PMDConfiguration();
            configuration.setDefaultLanguageVersion(
                    LanguageRegistry.PMD.getLanguageById("java").getVersion("21"));
            boolean anyRuleset = false;
            for (String ruleset : PMD_RULESETS) {
                try {
                    configuration.addRuleSet(ruleset);
                    anyRuleset = true;
                } catch (Exception ex) {
                    log.info("PMD ruleset {} did not load", ruleset);
                }
            }
            if (!anyRuleset) {
                return didNotRun("pmd");
            }
            configuration.setInputPathList(sources);
            List<QualityFinding> findings = new ArrayList<>();
            try (PmdAnalysis pmd = PmdAnalysis.create(configuration)) {
                Report report = pmd.performAnalysisAndCollectReport();
                for (RuleViolation violation : report.getViolations()) {
                    String rule = violation.getRule() == null || violation.getRule().getName() == null
                            ? "pmd"
                            : violation.getRule().getName();
                    var location = violation.getLocation();
                    String reportedPath = "";
                    if (location != null && location.getFileId() != null) {
                        String absolute = location.getFileId().getAbsolutePath();
                        reportedPath = absolute == null || absolute.isBlank()
                                ? location.getFileId().getFileName()
                                : absolute;
                    }
                    String path = relative(workspace, reportedPath);
                    int line = location == null ? 0 : location.getStartLine();
                    String message = violation.getDescription() == null ? "" : violation.getDescription().trim();
                    findings.add(finding("pmd", rule, "WARNING", path, line, message));
                }
            }
            log.info("PMD reported {} findings", findings.size());
            return new QualityToolReport("pmd", findings, false, "pmd");
        } catch (Exception ex) {
            log.info("PMD did not run: {}", SecretRedactor.sanitizeError(ex.getMessage()));
            return didNotRun("pmd");
        } finally {
            Thread.currentThread().setContextClassLoader(previous);
        }
    }

    private QualityToolReport runSpotBugs(UUID evaluationId, Path workspace) {
        Path scratch = null;
        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        try {
            List<Path> classRoots = classRoots(workspace);
            if (classRoots.isEmpty() && Files.isRegularFile(workspace.resolve("pom.xml"))) {
                compileWithMaven(evaluationId, workspace);
                classRoots = classRoots(workspace);
            }
            if (classRoots.isEmpty() && !Files.isRegularFile(workspace.resolve("pom.xml"))) {
                scratch = compileWithJavac(workspace);
                if (scratch != null) {
                    classRoots = classRoots(scratch);
                }
            }
            if (classRoots.isEmpty()) {
                return didNotRun("spotbugs");
            }
            Thread.currentThread().setContextClassLoader(FindBugs2.class.getClassLoader());
            Project project = new Project();
            for (Path root : classRoots) {
                project.addFile(root.toString());
            }
            project.addSourceDir(workspace.toString());
            BugCollectionBugReporter reporter = new BugCollectionBugReporter(project);
            reporter.setPriorityThreshold(Priorities.LOW_PRIORITY);
            FindBugs2 engine = new FindBugs2();
            engine.setProject(project);
            engine.setBugReporter(reporter);
            engine.setDetectorFactoryCollection(DetectorFactoryCollection.instance());
            engine.setUserPreferences(UserPreferences.createDefaultUserPreferences());
            engine.execute();
            BugCollection bugs = reporter.getBugCollection();
            List<QualityFinding> findings = new ArrayList<>();
            if (bugs != null) {
                for (BugInstance bug : bugs) {
                    String rule = bug.getType() == null || bug.getType().isBlank() ? "spotbugs" : bug.getType();
                    SourceLineAnnotation source = bug.getPrimarySourceLineAnnotation();
                    String path = source == null ? "" : relative(workspace, source.getSourcePath());
                    int line = source == null ? 0 : source.getStartLine();
                    String message = spotBugsMessage(bug);
                    String severity = switch (bug.getPriority()) {
                        case Priorities.HIGH_PRIORITY -> "ERROR";
                        case Priorities.NORMAL_PRIORITY -> "WARNING";
                        default -> "INFO";
                    };
                    findings.add(finding("spotbugs", rule, severity, path, line, message));
                }
            }
            log.info("SpotBugs reported {} findings", findings.size());
            return new QualityToolReport("spotbugs", findings, false, "spotbugs");
        } catch (Exception ex) {
            log.info("SpotBugs did not run: {}", SecretRedactor.sanitizeError(ex.getMessage()));
            return didNotRun("spotbugs");
        } finally {
            Thread.currentThread().setContextClassLoader(previous);
            deleteTree(scratch);
        }
    }

    private void compileWithMaven(UUID evaluationId, Path workspace) {
        if (evaluationId == null || isolatorService == null || executionProperties == null) {
            return;
        }
        try {
            IsolationResult result = isolatorService.runStage(
                    evaluationId,
                    ExecutionStage.QUALITY,
                    workspace,
                    null,
                    List.of("mvn", "-B", "-DskipTests", "compile", "-Dmaven.repo.local=/tmp/m2"),
                    Math.max(executionProperties.getDefaultMemoryMb(), MAVEN_COMPILE_MEMORY_MB),
                    executionProperties.getDefaultCpuLimit(),
                    MAVEN_COMPILE_TIMEOUT_SEC,
                    true,
                    executionProperties.resolvedJavaRunnerImage(),
                    java.util.Map.of(
                            "HOME", "/tmp",
                            "MAVEN_OPTS", "-Dmaven.repo.local=/tmp/m2"
                    )
            );
            int exit = result == null ? -1 : result.exitCode();
            if (exit != 0) {
                log.info("Maven compile for SpotBugs exited {}", exit);
            }
        } catch (Exception ex) {
            log.info("Maven compile for SpotBugs did not run: {}", SecretRedactor.sanitizeError(ex.getMessage()));
        }
    }

    private static Path compileWithJavac(Path workspace) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            return null;
        }
        List<Path> sources = javaSources(workspace);
        if (sources.isEmpty()) {
            return null;
        }
        Path out;
        try {
            out = Files.createTempDirectory("agentbench-quality-classes");
        } catch (Exception ex) {
            return null;
        }
        try (StandardJavaFileManager files = compiler.getStandardFileManager(null, Locale.ROOT, StandardCharsets.UTF_8)) {
            List<String> options = List.of("-d", out.toString(), "-g", "-encoding", "UTF-8");
            Boolean together = compiler.getTask(
                    null,
                    files,
                    null,
                    options,
                    null,
                    files.getJavaFileObjectsFromFiles(sources.stream().map(Path::toFile).toList())
            ).call();
            if (!Boolean.TRUE.equals(together)) {
                for (Path source : sources) {
                    compiler.getTask(
                            null,
                            files,
                            null,
                            options,
                            null,
                            files.getJavaFileObjectsFromFiles(List.of(source.toFile()))
                    ).call();
                }
            }
            if (classRoots(out).isEmpty()) {
                deleteTree(out);
                return null;
            }
            return out;
        } catch (Exception ex) {
            deleteTree(out);
            return null;
        }
    }

    private static Path materializeCheckstyleConfig() throws Exception {
        String[] bundled = {"/sun_checks.xml", "/google_checks.xml", "/quality/checkstyle.xml"};
        for (String resource : bundled) {
            InputStream fromTool = Checker.class.getResourceAsStream(resource);
            InputStream in = fromTool != null ? fromTool : JavaQualityToolchain.class.getResourceAsStream(resource);
            if (in == null) {
                continue;
            }
            try (in) {
                Path file = Files.createTempFile("agentbench-checkstyle-", ".xml");
                Files.copy(in, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                return file;
            } catch (Exception ex) {
                log.info("Checkstyle config {} did not load", resource);
            }
        }
        throw new IllegalStateException("checkstyle config missing");
    }

    static List<Path> javaSources(Path workspace) {
        List<Path> sources = new ArrayList<>();
        walk(workspace, true, (path, attrs) -> {
            if (attrs.isRegularFile() && path.getFileName().toString().endsWith(".java") && attrs.size() < 2_000_000) {
                sources.add(path);
            }
        });
        sources.sort(Comparator.comparing(Path::toString));
        return sources;
    }

    static List<Path> classRoots(Path workspace) {
        List<Path> roots = new ArrayList<>();
        walk(workspace, false, (path, attrs) -> {
            if (!attrs.isRegularFile() || !path.getFileName().toString().endsWith(".class")) {
                return;
            }
            Path dir = path.getParent();
            while (dir != null && dir.startsWith(workspace)) {
                if (dir.getFileName() != null && "classes".equals(dir.getFileName().toString())) {
                    if (!roots.contains(dir)) {
                        roots.add(dir);
                    }
                    return;
                }
                dir = dir.getParent();
            }
            if (!roots.contains(workspace)) {
                roots.add(workspace);
            }
        });
        return roots;
    }

    private static void walk(Path root, boolean skipBuildOutput, Visitor visitor) {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    String name = dir.getFileName() == null ? "" : dir.getFileName().toString();
                    if (dir.equals(root)) {
                        return FileVisitResult.CONTINUE;
                    }
                    if (name.equals(".git") || name.equals("node_modules")) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    if (skipBuildOutput && (name.equals("target") || name.equals("build"))) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    try {
                        visitor.accept(file, attrs);
                    } catch (Exception ignored) {
                        // one unreadable file does not fail the tool
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (Exception ex) {
            log.info("Workspace walk stopped: {}", SecretRedactor.sanitizeError(ex.getMessage()));
        }
    }

    private static String spotBugsMessage(BugInstance bug) {
        try {
            if (bug.getBugPattern() != null && bug.getBugPattern().getShortDescription() != null) {
                return bug.getBugPattern().getShortDescription().trim();
            }
        } catch (Exception ignored) {
            // fall through
        }
        return bug.getType() == null ? "" : bug.getType();
    }

    private static QualityFinding finding(
            String tool, String rule, String severity, String path, int line, String message) {
        String safeMessage = message == null ? "" : message.replaceAll("\\s+", " ").trim();
        if (safeMessage.length() > 500) {
            safeMessage = safeMessage.substring(0, 500);
        }
        String fp = tool + "|" + rule + "|" + path + "|" + line + "|" + safeMessage;
        return new QualityFinding(tool, rule, severity, path, Math.max(line, 0), safeMessage, fp);
    }

    static String relative(Path workspace, String reported) {
        if (reported == null || reported.isBlank() || "null".equals(reported)) {
            return "";
        }
        String cleaned = reported.replace('\\', '/');
        try {
            Path path = Path.of(reported);
            if (path.isAbsolute() && workspace != null) {
                Path normal = path.toAbsolutePath().normalize();
                Path root = workspace.toAbsolutePath().normalize();
                if (normal.startsWith(root)) {
                    return root.relativize(normal).toString().replace('\\', '/');
                }
            }
        } catch (Exception ignored) {
            return cleaned;
        }
        return cleaned;
    }

    private static QualityToolReport didNotRun(String tool) {
        return QualityToolReport.stub(tool, DID_NOT_RUN);
    }

    private static void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (Exception ignored) {
            // temp config
        }
    }

    private static void deleteTree(Path root) {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (var walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (Exception ignored) {
                    // temp classes
                }
            });
        } catch (Exception ignored) {
            // temp classes
        }
    }

    @FunctionalInterface
    private interface Visitor {
        void accept(Path path, BasicFileAttributes attrs) throws Exception;
    }
}
