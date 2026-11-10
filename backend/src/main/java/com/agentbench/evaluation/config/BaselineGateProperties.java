package com.agentbench.evaluation.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Baseline confirmation gate knobs (R5). When enabled, the baseline stage requires
 * stable repeated outcomes before the evaluation advances to agent invocation.
 */
@ConfigurationProperties(prefix = "agentbench.evaluation.baseline")
public class BaselineGateProperties {

    /**
     * When true (or when task evalConfig.requireBaselineConfirmation is true),
     * baseline must be confirmed via repeated stable runs before INVOKING_AGENT.
     */
    private boolean confirmationEnabled = false;

    /**
     * Minimum confirmation runs when the gate is on (clamped 2–10). Task
     * {@code evalConfig.baselineConfirmRuns} may raise this per evaluation.
     */
    private int confirmationRuns = 2;

    public boolean isConfirmationEnabled() {
        return confirmationEnabled;
    }

    public void setConfirmationEnabled(boolean confirmationEnabled) {
        this.confirmationEnabled = confirmationEnabled;
    }

    public int getConfirmationRuns() {
        return confirmationRuns;
    }

    public void setConfirmationRuns(int confirmationRuns) {
        this.confirmationRuns = confirmationRuns;
    }
}
