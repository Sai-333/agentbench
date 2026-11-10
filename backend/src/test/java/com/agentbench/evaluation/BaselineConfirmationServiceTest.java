package com.agentbench.evaluation;

import com.agentbench.evaluation.config.BaselineGateProperties;
import com.agentbench.evaluation.service.BaselineConfirmationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BaselineConfirmationServiceTest {

    private BaselineGateProperties properties;
    private BaselineConfirmationService service;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        properties = new BaselineGateProperties();
        properties.setConfirmationEnabled(false);
        properties.setConfirmationRuns(2);
        service = new BaselineConfirmationService(properties);
    }

    @Test
    void gateOffUsesFlakyRepeatsOnly() {
        ObjectNode task = objectMapper.createObjectNode();
        assertFalse(service.isGateEnabled(task));
        assertEquals(1, service.resolveConfirmationRuns(task, 1));
        assertEquals(3, service.resolveConfirmationRuns(task, 3));
    }

    @Test
    void platformGateEnablesMinimumTwoRuns() {
        properties.setConfirmationEnabled(true);
        ObjectNode task = objectMapper.createObjectNode();
        assertTrue(service.isGateEnabled(task));
        assertEquals(2, service.resolveConfirmationRuns(task, 1));
    }

    @Test
    void taskOverrideCanRaiseRunsAndEnableGate() {
        ObjectNode task = objectMapper.createObjectNode();
        task.putObject("evalConfig")
                .put("requireBaselineConfirmation", true)
                .put("baselineConfirmRuns", 4);
        assertTrue(service.isGateEnabled(task));
        assertEquals(4, service.resolveConfirmationRuns(task, 2));
    }

    @Test
    void blockingFlakyKeysIgnoreQuarantined() {
        Set<String> blocking = service.blockingFlakyKeys(
                Set.of("a", "b", "c"),
                Set.of("b"));
        assertEquals(Set.of("a", "c"), blocking);
    }
}
