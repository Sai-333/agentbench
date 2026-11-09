package com.agentbench.evaluation.service;

import com.agentbench.evaluation.infra.EvaluationRepository;
import com.agentbench.evaluation.worker.EvaluationOutboxPublisher;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Processes outbox commands inline (used by integration tests when RabbitMQ is disabled).
 */
@Component
public class EvaluationSyncRunner {

    private final EvaluationRepository evaluationRepository;
    private final EvaluationStageProcessor stageProcessor;
    private final ObjectMapper objectMapper;

    public EvaluationSyncRunner(
            EvaluationRepository evaluationRepository,
            EvaluationStageProcessor stageProcessor,
            EvaluationOutboxPublisher ignored,
            ObjectMapper objectMapper) {
        this.evaluationRepository = evaluationRepository;
        this.stageProcessor = stageProcessor;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public int drainAndProcess(int maxCycles) {
        int total = 0;
        for (int cycle = 0; cycle < maxCycles; cycle++) {
            var rows = evaluationRepository.listUnpublished(20);
            if (rows.isEmpty()) {
                break;
            }
            for (EvaluationRepository.OutboxRow row : rows) {
                try {
                    EvaluationCommand command = objectMapper.readValue(row.payload(), EvaluationCommand.class);
                    evaluationRepository.markPublished(row.id());
                    stageProcessor.process(command);
                    total++;
                } catch (Exception ex) {
                    evaluationRepository.incrementPublishAttempts(row.id());
                    throw new IllegalStateException("Sync stage processing failed: " + ex.getMessage(), ex);
                }
            }
        }
        return total;
    }
}
