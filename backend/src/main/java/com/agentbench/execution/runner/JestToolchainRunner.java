package com.agentbench.execution.runner;

import com.agentbench.execution.config.ExecutionProperties;
import com.agentbench.execution.service.ExecutionIsolatorService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Jest (Node) toolchain. Uses the {@code jest-junit} reporter to emit XML under
 * {@code reports/} for {@link com.agentbench.execution.service.JUnitXmlParser} ingest.
 * Prefers a prebuilt runner image with jest + jest-junit installed (network off by default).
 */
@Component
@Order(120)
public class JestToolchainRunner extends AbstractJUnitXmlToolchainRunner {

    public static final String ID = "jest";

    public JestToolchainRunner(
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
        return frameworkMatches(framework, "jest")
                || "node".equalsIgnoreCase(framework == null ? "" : framework.trim())
                || "nodejs".equalsIgnoreCase(framework == null ? "" : framework.trim());
    }

    @Override
    protected List<String> defaultCommand(SuiteExecutionRequest request) {
        // sh -c so we can mkdir reports and invoke jest with multiple reporters.
        return List.of(
                "sh", "-c",
                "mkdir -p reports && jest --ci --color=false "
                        + "--reporters=default --reporters=jest-junit"
        );
    }

    @Override
    protected String defaultReportsDir() {
        return "reports";
    }

    @Override
    protected String resolveImage(SuiteExecutionRequest request) {
        return properties.resolvedNodeRunnerImage();
    }

    @Override
    protected Map<String, String> defaultEnv(SuiteExecutionRequest request) {
        Map<String, String> env = super.defaultEnv(request);
        env.put("JEST_JUNIT_OUTPUT_DIR", "reports");
        env.put("JEST_JUNIT_OUTPUT_NAME", "junit.xml");
        env.put("npm_config_cache", "/tmp/npm-cache");
        env.put("NPM_CONFIG_CACHE", "/tmp/npm-cache");
        return env;
    }
}
