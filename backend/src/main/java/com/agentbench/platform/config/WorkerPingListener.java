package com.agentbench.platform.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Worker-profile stub: consumes a platform ping message to prove Compose wiring.
 */
@Component
@Profile("worker")
public class WorkerPingListener {

    private static final Logger log = LoggerFactory.getLogger(WorkerPingListener.class);

    @RabbitListener(queues = RabbitConfig.PLATFORM_PING_QUEUE)
    public void onPing(String payload) {
        log.info("Worker received platform ping: {}", payload);
    }
}
