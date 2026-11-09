package com.agentbench.execution.quality;

/**
 * Optional/extensible Sonar stub. Not invoked for scoring until a real Sonar adapter is wired.
 * Kept so M7 leaves a clear extension point without inventing Sonar scores.
 */
public final class SonarQualityAnalyzerStub {

    public static final String TOOL = "sonar";

    private SonarQualityAnalyzerStub() {
    }

    public static QualityToolReport analyzePlaceholder() {
        return QualityToolReport.stub(TOOL, "sonar_optional_extensible_stub_m7");
    }
}
