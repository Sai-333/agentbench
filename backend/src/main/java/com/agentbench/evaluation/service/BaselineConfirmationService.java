package com.agentbench.evaluation.service;

import com.agentbench.evaluation.config.BaselineGateProperties;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Baseline confirmation gate (R5): when configured, require repeated stable
 * baseline outcomes before agent comparison proceeds.
 *
 * <p>Uses the same repeat harness as {@link FlakyDetectionService}; the gate
 * fails closed if any non-quarantined case is inconsistent across confirmation runs.
 */
@Service
public class BaselineConfirmationService {

    private final BaselineGateProperties properties;

    public BaselineConfirmationService(BaselineGateProperties properties) {
        this.properties = properties;
    }

    public boolean isGateEnabled(JsonNode task) {
        if (task != null && task.path("evalConfig").path("requireBaselineConfirmation").asBoolean(false)) {
            return true;
        }
        return properties.isConfirmationEnabled();
    }

    /**
     * Resolve how many suite runs to use for baseline when the gate is on.
     * Always ≥ 2 when enabled. Honors task {@code baselineConfirmRuns} and
     * platform {@code confirmation-runs}, and never undercuts flaky repeats.
     */
    public int resolveConfirmationRuns(JsonNode task, int flakyRepeatRuns) {
        if (!isGateEnabled(task)) {
            return Math.max(1, flakyRepeatRuns);
        }
        int fromTask = task == null ? -1 : task.path("evalConfig").path("baselineConfirmRuns").asInt(-1);
        int configured = fromTask > 0 ? fromTask : properties.getConfirmationRuns();
        int runs = Math.max(2, configured);
        runs = Math.max(runs, Math.max(1, flakyRepeatRuns));
        return Math.min(runs, 10);
    }

    /**
     * Non-quarantined flaky keys that block confirmation.
     */
    public Set<String> blockingFlakyKeys(Set<String> flakyCaseKeys, Set<String> quarantinedCaseKeys) {
        Set<String> blocking = new LinkedHashSet<>();
        if (flakyCaseKeys == null) {
            return blocking;
        }
        for (String key : flakyCaseKeys) {
            if (key == null || key.isBlank()) {
                continue;
            }
            if (quarantinedCaseKeys != null && quarantinedCaseKeys.contains(key)) {
                continue;
            }
            blocking.add(key);
        }
        return blocking;
    }
}
