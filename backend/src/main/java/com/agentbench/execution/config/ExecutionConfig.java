package com.agentbench.execution.config;

import com.agentbench.execution.quality.SonarProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({
        ExecutionProperties.class,
        ArtifactRetentionProperties.class,
        SonarProperties.class
})
public class ExecutionConfig {
}
