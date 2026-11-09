package com.agentbench.evaluation.worker;

import com.agentbench.evaluation.infra.EvaluationRepository;
import com.agentbench.evaluation.service.EvaluationCommand;
import com.agentbench.platform.config.RabbitConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Publishes transactional outbox rows to RabbitMQ. Also exposes a sync drain helper for tests.
 */
@Component
public class EvaluationOutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(EvaluationOutboxPublisher.class);
    private static final int MAX_PUBLISH_ATTEMPTS = 10;

    private final EvaluationRepository evaluationRepository;
    private final ObjectProvider<RabbitTemplate> rabbitTemplateProvider;
    private final ObjectMapper objectMapper;

    public EvaluationOutboxPublisher(
            EvaluationRepository evaluationRepository,
            ObjectProvider<RabbitTemplate> rabbitTemplateProvider,
            ObjectMapper objectMapper) {
        this.evaluationRepository = evaluationRepository;
        this.rabbitTemplateProvider = rabbitTemplateProvider;
        this.objectMapper = objectMapper;
    }

    @Scheduled(fixedDelayString = "${agentbench.evaluation.outbox-poll-ms:1000}")
    public void publishPending() {
        RabbitTemplate rabbitTemplate = rabbitTemplateProvider.getIfAvailable();
        if (rabbitTemplate == null) {
            return;
        }
        drain(25, true);
    }

    /**
     * Drains unpublished outbox events. When {@code publishToBroker} is false, returns
     * parsed commands without marking published (caller processes inline).
     */
    @Transactional
    public int drain(int limit, boolean publishToBroker) {
        RabbitTemplate rabbitTemplate = rabbitTemplateProvider.getIfAvailable();
        var rows = evaluationRepository.listUnpublished(limit);
        int processed = 0;
        for (EvaluationRepository.OutboxRow row : rows) {
            try {
                EvaluationCommand command = objectMapper.readValue(row.payload(), EvaluationCommand.class);
                if (publishToBroker) {
                    if (rabbitTemplate == null) {
                        return processed;
                    }
                    rabbitTemplate.convertAndSend(
                            RabbitConfig.EVALUATION_EXCHANGE,
                            RabbitConfig.EVALUATION_ROUTING_KEY,
                            command
                    );
                    evaluationRepository.markPublished(row.id());
                } else {
                    // Inline mode: mark published so sync runner owns delivery.
                    evaluationRepository.markPublished(row.id());
                }
                processed++;
            } catch (Exception ex) {
                log.warn("Failed to publish outbox event {}: {}", row.id(), ex.getMessage());
                evaluationRepository.incrementPublishAttempts(row.id());
                if (row.publishAttempts() + 1 >= MAX_PUBLISH_ATTEMPTS) {
                    // Leave unpublished for ops inspection; DLQ path covers broker rejects.
                    log.error("Outbox event {} exceeded max publish attempts", row.id());
                }
            }
        }
        return processed;
    }

    @Transactional(readOnly = true)
    public java.util.List<EvaluationCommand> peekUnpublished(int limit) {
        return evaluationRepository.listUnpublished(limit).stream()
                .map(row -> {
                    try {
                        return objectMapper.readValue(row.payload(), EvaluationCommand.class);
                    } catch (Exception ex) {
                        throw new IllegalStateException(ex);
                    }
                })
                .toList();
    }
}
