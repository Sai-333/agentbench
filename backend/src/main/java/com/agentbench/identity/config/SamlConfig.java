package com.agentbench.identity.config;

import com.agentbench.identity.auth.SamlProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(SamlProperties.class)
public class SamlConfig {
}
