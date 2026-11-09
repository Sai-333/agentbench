package com.agentbench.evaluation.worker;

import com.agentbench.agents.infra.SecretRedactor;
import com.agentbench.platform.config.RabbitConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Observes poison evaluation commands that exhausted listener retries and landed in the DLQ.
 * Does not auto-replay — operators inspect and requeue deliberately (see runbook).
 */
@Component
@ConditionalOnProperty(name = "agentbench.evaluation.listener-enabled", havingValue = "true", matchIfMissing = true)
public class EvaluationDlqListener {

    private static final Logger log = LoggerFactory.getLogger(EvaluationDlqListener.class);

    @RabbitListener(queues = RabbitConfig.EVALUATION_DLQ)
    public void onDeadLetter(Message message) {
        String body = message == null || message.getBody() == null
                ? ""
                : SecretRedactor.redact(new String(message.getBody()));
        Object death = message == null || message.getMessageProperties() == null
                ? null
                : message.getMessageProperties().getHeaders().get("x-death");
        log.error("Evaluation command dead-lettered (manual inspect/requeue). x-death={} body={}",
                death, body.length() > 500 ? body.substring(0, 500) + "…" : body);
    }
}
