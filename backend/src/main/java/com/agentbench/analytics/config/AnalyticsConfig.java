package com.agentbench.analytics.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
@EnableConfigurationProperties(PublicLeaderboardProperties.class)
public class AnalyticsConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
