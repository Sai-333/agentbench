package com.agentbench.identity.config;

import com.agentbench.identity.auth.OidcProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(OidcProperties.class)
public class OidcConfig {
}
