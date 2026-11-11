package com.agentbench.evaluation;

import com.agentbench.evaluation.infra.FlakeQuarantineRepository;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlakeQuarantineRepositorySqlTest {

    @Test
    void activeListKeepsWhitespaceBeforeOrderBy() {
        String activeOnly = FlakeQuarantineRepository.listSql(false);
        String all = FlakeQuarantineRepository.listSql(true);
        assertTrue(activeOnly.contains("active = TRUE"));
        assertFalse(activeOnly.contains("TRUEORDER"));
        assertTrue(activeOnly.contains("ORDER BY created_at DESC"));
        assertFalse(all.contains("active = TRUE"));
        assertTrue(all.contains("ORDER BY created_at DESC"));
    }
}
