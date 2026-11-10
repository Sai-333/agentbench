package com.agentbench.execution.runner;

import com.agentbench.execution.config.ExecutionProperties;
import com.agentbench.execution.service.ExecutionIsolatorService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Maven / Surefire / JUnit 5 toolchain. Default command {@code mvn -B -q test} with local
 * repo under {@code /tmp/m2}; reports under {@code target/surefire-reports}.
 */
@Component
@Order(100)
public class MavenJUnitToolchainRunner extends AbstractJUnitXmlToolchainRunner {

    public static final String ID = "maven-junit";

    public MavenJUnitToolchainRunner(
            ExecutionIsolatorService isolatorService,
            ExecutionProperties properties,
            ObjectMapper objectMapper) {
        super(isolatorService, properties, objectMapper);
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public boolean supports(String framework) {
        // Do not claim bare "java" — that may be fixture-only demos.
        return frameworkMatches(framework, "junit", "surefire", "maven");
    }

    @Override
    protected List<String> defaultCommand(SuiteExecutionRequest request) {
        return List.of(
                "mvn", "-B", "-q", "test",
                "-Dmaven.repo.local=/tmp/m2"
        );
    }

    @Override
    protected String defaultReportsDir() {
        return "target/surefire-reports";
    }

    @Override
    protected String resolveImage(SuiteExecutionRequest request) {
        return properties.resolvedJavaRunnerImage();
    }

    @Override
    protected Map<String, String> defaultEnv(SuiteExecutionRequest request) {
        Map<String, String> env = super.defaultEnv(request);
        env.put("MAVEN_OPTS", "-Dmaven.repo.local=/tmp/m2");
        return env;
    }
}
