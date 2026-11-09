package com.agentbench.platform.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Messaging topology: platform ping + evaluation commands with DLQ / retry.
 */
@Configuration
public class RabbitConfig {

    public static final String PLATFORM_PING_QUEUE = "agentbench.platform.ping";

    public static final String EVALUATION_EXCHANGE = "evaluation.commands";
    public static final String EVALUATION_QUEUE = "evaluation.commands.q";
    public static final String EVALUATION_DLX = "evaluation.commands.dlx";
    public static final String EVALUATION_DLQ = "evaluation.commands.dlq";
    public static final String EVALUATION_ROUTING_KEY = "evaluation.advance";

    @Bean
    Queue platformPingQueue() {
        return QueueBuilder.durable(PLATFORM_PING_QUEUE).build();
    }

    @Bean
    DirectExchange evaluationExchange() {
        return new DirectExchange(EVALUATION_EXCHANGE, true, false);
    }

    @Bean
    DirectExchange evaluationDeadLetterExchange() {
        return new DirectExchange(EVALUATION_DLX, true, false);
    }

    @Bean
    Queue evaluationQueue() {
        return QueueBuilder.durable(EVALUATION_QUEUE)
                .withArgument("x-dead-letter-exchange", EVALUATION_DLX)
                .withArgument("x-dead-letter-routing-key", EVALUATION_ROUTING_KEY)
                .build();
    }

    @Bean
    Queue evaluationDeadLetterQueue() {
        return QueueBuilder.durable(EVALUATION_DLQ).build();
    }

    @Bean
    Binding evaluationBinding(Queue evaluationQueue, DirectExchange evaluationExchange) {
        return BindingBuilder.bind(evaluationQueue).to(evaluationExchange).with(EVALUATION_ROUTING_KEY);
    }

    @Bean
    Binding evaluationDlqBinding(Queue evaluationDeadLetterQueue, DirectExchange evaluationDeadLetterExchange) {
        return BindingBuilder.bind(evaluationDeadLetterQueue)
                .to(evaluationDeadLetterExchange)
                .with(EVALUATION_ROUTING_KEY);
    }

    @Bean
    MessageConverter jacksonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }
}
