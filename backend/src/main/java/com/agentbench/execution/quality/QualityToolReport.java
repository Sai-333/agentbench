package com.agentbench.execution.quality;

import java.util.List;

public record QualityToolReport(
        String tool,
        List<QualityFinding> findings,
        boolean stub,
        String notes
) {
    public QualityToolReport {
        findings = findings == null ? List.of() : List.copyOf(findings);
        notes = notes == null ? "" : notes;
    }

    public static QualityToolReport stub(String tool, String notes) {
        return new QualityToolReport(tool, List.of(), true, notes);
    }
}
