package com.agentbench.execution.runner;

import com.agentbench.execution.config.ExecutionProperties;
import com.agentbench.execution.service.ExecutionIsolatorService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * pytest toolchain. Emits JUnit XML via {@code --junitxml=reports/junit.xml} for ingest
 * through {@link com.agentbench.execution.service.JUnitXmlParser}.
 */
@Component
@Order(110)
public class PytestToolchainRunner extends AbstractJUnitXmlToolchainRunner {

    public static final String ID = "pytest";

    public PytestToolchainRunner(
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
        return frameworkMatches(framework, "pytest", "py.test")
                || "python".equalsIgnoreCase(framework == null ? "" : framework.trim());
    }

    @Override
    protected List<String> defaultCommand(SuiteExecutionRequest request) {
        return List.of(
                "pytest",
                "--junitxml=reports/junit.xml",
                "-q",
                "--cache-clear"
        );
    }

    @Override
    protected String defaultReportsDir() {
        return "reports";
    }

    @Override
    protected String resolveImage(SuiteExecutionRequest request) {
        return properties.resolvedPythonRunnerImage();
    }

    @Override
    protected Map<String, String> defaultEnv(SuiteExecutionRequest request) {
        Map<String, String> env = super.defaultEnv(request);
        env.put("PYTHONDONTWRITEBYTECODE", "1");
        env.put("PYTHONPYCACHEPREFIX", "/tmp/pycache");
        env.put("PYTEST_ADDOPTS", "--color=no");
        return env;
    }
}
