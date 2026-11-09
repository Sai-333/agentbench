package com.agentbench.evaluation.domain;

/**
 * Visible-suite baseline↔post transitions under comparable identities.
 * Hidden suites must never be labeled with these kinds (post-only pass rate).
 */
public enum TransitionKind {
    F2P,
    P2P,
    P2F,
    F2F;

    public static TransitionKind classify(TestCaseStatus baseline, TestCaseStatus post) {
        if (baseline == null || post == null) {
            throw new IllegalArgumentException("Comparable baseline and post statuses are required");
        }
        if (!isComparable(baseline) || !isComparable(post)) {
            throw new IllegalArgumentException(
                    "Only PASS/FAIL statuses produce transition labels; got " + baseline + " → " + post);
        }
        if (baseline == TestCaseStatus.FAIL && post == TestCaseStatus.PASS) {
            return F2P;
        }
        if (baseline == TestCaseStatus.PASS && post == TestCaseStatus.PASS) {
            return P2P;
        }
        if (baseline == TestCaseStatus.PASS && post == TestCaseStatus.FAIL) {
            return P2F;
        }
        return F2F;
    }

    public static boolean isComparable(TestCaseStatus status) {
        return status == TestCaseStatus.PASS || status == TestCaseStatus.FAIL;
    }
}
