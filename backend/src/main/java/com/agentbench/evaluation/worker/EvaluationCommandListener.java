package com.agentbench.evaluation.worker;

import com.agentbench.evaluation.service.EvaluationCommand;
import com.agentbench.evaluation.service.EvaluationStageProcessor;
import com.agentbench.platform.config.RabbitConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Consumes evaluation stage commands. Ack after stage checkpoint inside the processor.
 */
@Component
@ConditionalOnProperty(name = "agentbench.evaluation.listener-enabled", havingValue = "true", matchIfMissing = true)
public class EvaluationCommandListener {

    private static final Logger log = LoggerFactory.getLogger(EvaluationCommandListener.class);

    private final EvaluationStageProcessor stageProcessor;

    public EvaluationCommandListener(EvaluationStageProcessor stageProcessor) {
        this.stageProcessor = stageProcessor;
    }

    @RabbitListener(queues = RabbitConfig.EVALUATION_QUEUE)
    public void onCommand(EvaluationCommand command) {
        log.info("Received evaluation command {} → {} attempt={}",
                command.evaluationId(), command.targetState(), command.attempt());
        stageProcessor.process(command);
    }
}
