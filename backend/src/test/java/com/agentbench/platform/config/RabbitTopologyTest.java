package com.agentbench.platform.config;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies evaluation command queue is wired with a dead-letter exchange/queue (M9 recovery).
 */
class RabbitTopologyTest {

    @Test
    void evaluationQueueDeadLettersToDlq() {
        RabbitConfig config = new RabbitConfig();
        Queue main = config.evaluationQueue();
        Queue dlq = config.evaluationDeadLetterQueue();
        DirectExchange dlx = config.evaluationDeadLetterExchange();
        Binding dlqBinding = config.evaluationDlqBinding(dlq, dlx);

        assertEquals(RabbitConfig.EVALUATION_QUEUE, main.getName());
        assertEquals(RabbitConfig.EVALUATION_DLQ, dlq.getName());
        assertEquals(RabbitConfig.EVALUATION_DLX, dlx.getName());
        assertEquals(RabbitConfig.EVALUATION_DLX, main.getArguments().get("x-dead-letter-exchange"));
        assertEquals(RabbitConfig.EVALUATION_ROUTING_KEY, main.getArguments().get("x-dead-letter-routing-key"));
        assertTrue(main.isDurable());
        assertTrue(dlq.isDurable());
        assertEquals(RabbitConfig.EVALUATION_DLQ, dlqBinding.getDestination());
        assertEquals(RabbitConfig.EVALUATION_ROUTING_KEY, dlqBinding.getRoutingKey());
    }
}
