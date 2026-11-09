package com.agentbench.evaluation.worker;

import com.agentbench.evaluation.domain.Evaluation;
import com.agentbench.evaluation.domain.EvaluationState;
import com.agentbench.evaluation.domain.FailureClass;
import com.agentbench.evaluation.domain.FailureCode;
import com.agentbench.evaluation.infra.EvaluationRepository;
import com.agentbench.evaluation.service.EvaluationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Marks long-running evaluations as TIMED_OUT based on org max duration.
 */
@Component
public class EvaluationTimeoutWatchdog {

    private static final Logger log = LoggerFactory.getLogger(EvaluationTimeoutWatchdog.class);

    private final NamedParameterJdbcTemplate jdbc;
    private final EvaluationRepository evaluationRepository;
    private final EvaluationService evaluationService;

    public EvaluationTimeoutWatchdog(
            NamedParameterJdbcTemplate jdbc,
            EvaluationRepository evaluationRepository,
            EvaluationService evaluationService) {
        this.jdbc = jdbc;
        this.evaluationRepository = evaluationRepository;
        this.evaluationService = evaluationService;
    }

    @Scheduled(fixedDelayString = "${agentbench.evaluation.timeout-poll-ms:30000}")
    @Transactional
    public void sweep() {
        List<UUID> ids = jdbc.query("""
                SELECT e.id
                FROM evaluations.evaluations e
                LEFT JOIN evaluations.org_limits l ON l.org_id = e.org_id
                WHERE e.state NOT IN ('COMPLETED', 'FAILED', 'CANCELLED', 'TIMED_OUT')
                  AND e.started_at IS NOT NULL
                  AND e.started_at < now() - make_interval(secs => COALESCE(l.max_duration_sec, 1800))
                LIMIT 50
                """,
                new MapSqlParameterSource(),
                (rs, rowNum) -> rs.getObject("id", UUID.class));
        for (UUID id : ids) {
            Evaluation evaluation = evaluationRepository.findById(id).orElse(null);
            if (evaluation == null || evaluation.state().isTerminal()) {
                continue;
            }
            log.warn("Timing out evaluation {}", id);
            evaluationService.transitionTo(
                    evaluation,
                    EvaluationState.TIMED_OUT,
                    "Exceeded max duration",
                    "watchdog",
                    FailureClass.INFRASTRUCTURE,
                    FailureCode.TIMED_OUT.name(),
                    "Evaluation exceeded configured max duration"
            );
        }
    }
}
