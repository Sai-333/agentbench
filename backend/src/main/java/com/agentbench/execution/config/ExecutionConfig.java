package com.agentbench.execution.config;

import com.agentbench.execution.quality.NodeQualityProperties;
import com.agentbench.execution.quality.PythonQualityProperties;
import com.agentbench.execution.quality.SonarProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({
        ExecutionProperties.class,
        ArtifactRetentionProperties.class,
        SonarProperties.class,
        PythonQualityProperties.class,
        NodeQualityProperties.class
})
public class ExecutionConfig {
}
