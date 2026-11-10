package com.agentbench.evaluation;

import com.agentbench.evaluation.domain.FlakeQuarantine;
import com.agentbench.evaluation.infra.FlakeQuarantineRepository;
import com.agentbench.evaluation.service.FlakeQuarantineService;
import com.agentbench.identity.security.OrgAccessService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FlakeQuarantineServiceTest {

    @Mock
    FlakeQuarantineRepository repository;
    @Mock
    OrgAccessService orgAccessService;

    FlakeQuarantineService service;
    ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        service = new FlakeQuarantineService(repository, orgAccessService);
    }

    @Test
    void unionsDbCaseKeysTaskKeysAndSuiteFlag() {
        UUID orgId = UUID.randomUUID();
        UUID suiteId = UUID.randomUUID();
        when(repository.listActive(orgId)).thenReturn(List.of(
                new FlakeQuarantine(
                        UUID.randomUUID(), orgId, suiteId, "db.case", "flake", true,
                        null, Instant.now(), Instant.now(), null),
                new FlakeQuarantine(
                        UUID.randomUUID(), orgId, null, "org.wide", "flake", true,
                        null, Instant.now(), Instant.now(), null)
        ));

        ObjectNode suite = objectMapper.createObjectNode();
        ObjectNode task = objectMapper.createObjectNode();
        task.putObject("evalConfig").putArray("quarantinedCaseKeys").add("task.case");

        Set<String> keys = service.resolveQuarantinedCaseKeys(
                orgId, suiteId, suite, task, List.of("db.case", "org.wide", "task.case", "ok"));
        assertEquals(Set.of("db.case", "org.wide", "task.case"), keys);
    }

    @Test
    void suiteLevelQuarantineMarksAllKnownKeys() {
        UUID orgId = UUID.randomUUID();
        UUID suiteId = UUID.randomUUID();
        when(repository.listActive(orgId)).thenReturn(List.of(
                new FlakeQuarantine(
                        UUID.randomUUID(), orgId, suiteId, null, "whole suite", true,
                        null, Instant.now(), Instant.now(), null)
        ));
        ObjectNode suite = objectMapper.createObjectNode();
        ObjectNode task = objectMapper.createObjectNode();

        Set<String> keys = service.resolveQuarantinedCaseKeys(
                orgId, suiteId, suite, task, List.of("a", "b"));
        assertEquals(Set.of("a", "b"), keys);
    }

    @Test
    void suiteJsonFlagQuarantinesAllCases() {
        UUID orgId = UUID.randomUUID();
        when(repository.listActive(orgId)).thenReturn(List.of());
        ObjectNode suite = objectMapper.createObjectNode();
        suite.put("quarantined", true);
        ObjectNode task = objectMapper.createObjectNode();

        Set<String> keys = service.resolveQuarantinedCaseKeys(
                orgId, null, suite, task, List.of("x", "y"));
        assertEquals(Set.of("x", "y"), keys);
    }

    @Test
    void quarantineMetaHelpers() {
        assertTrue(FlakeQuarantineService.isQuarantinedMeta("{\"quarantined\":true}"));
        assertFalse(FlakeQuarantineService.isQuarantinedMeta("{}"));
        assertTrue(FlakeQuarantineService.quarantineMeta(true, true, false).contains("\"quarantined\":true"));
        assertTrue(FlakeQuarantineService.quarantineMeta(false, false, true).contains("neverF2P"));
    }
}
