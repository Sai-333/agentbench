package com.agentbench.evaluation.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(BaselineGateProperties.class)
public class EvaluationConfig {
}
