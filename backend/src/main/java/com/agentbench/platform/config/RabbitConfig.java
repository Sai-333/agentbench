package com.agentbench.platform.config;

import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Minimal messaging topology for Milestone 1 (platform ping).
 * Evaluation queues arrive in later milestones.
 */
@Configuration
public class RabbitConfig {

    public static final String PLATFORM_PING_QUEUE = "agentbench.platform.ping";

    @Bean
    Queue platformPingQueue() {
        return QueueBuilder.durable(PLATFORM_PING_QUEUE).build();
    }
}
