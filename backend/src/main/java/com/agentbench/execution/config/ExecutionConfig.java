package com.agentbench.execution.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({ExecutionProperties.class, ArtifactRetentionProperties.class})
public class ExecutionConfig {
}
