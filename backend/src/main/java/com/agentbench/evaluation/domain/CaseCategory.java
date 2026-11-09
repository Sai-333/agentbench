package com.agentbench.evaluation.domain;

public enum CaseCategory {
    TARGET,
    CONTROL,
    EDGE,
    HIDDEN,
    OTHER;

    public static CaseCategory fromRole(String role, boolean hiddenSuite) {
        if (role != null && !role.isBlank()) {
            CaseCategory fromExplicit = switch (role.trim().toLowerCase()) {
                case "target" -> TARGET;
                case "control" -> CONTROL;
                case "edge", "edge_case", "edge-case" -> EDGE;
                case "hidden" -> HIDDEN;
                default -> null;
            };
            if (fromExplicit == CaseCategory.EDGE) {
                // Edge tags remain authoritative even inside a hidden suite.
                return EDGE;
            }
            if (fromExplicit != null && !hiddenSuite) {
                return fromExplicit;
            }
        }
        if (hiddenSuite) {
            return HIDDEN;
        }
        if (role == null || role.isBlank()) {
            return OTHER;
        }
        return OTHER;
    }
}
