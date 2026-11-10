package com.agentbench.evaluation.service;

import com.agentbench.evaluation.api.dto.FlakeQuarantineDtos;
import com.agentbench.evaluation.domain.FlakeQuarantine;
import com.agentbench.evaluation.infra.FlakeQuarantineRepository;
import com.agentbench.identity.domain.Membership;
import com.agentbench.identity.domain.OrgRole;
import com.agentbench.identity.security.OrgAccessService;
import com.agentbench.platform.error.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Known-flake quarantine for scoring fairness (threat-model R5).
 *
 * <p>Sources (union):
 * <ul>
 *   <li>Org admin CRUD rows in {@code evaluations.flake_quarantines}</li>
 *   <li>Task {@code evalConfig.quarantinedCaseKeys}</li>
 *   <li>Suite flag {@code quarantined: true} (all cases in that suite)</li>
 * </ul>
 *
 * <p>Quarantined cases are still recorded for transparency but do not contribute
 * F2P/P2P/P2F/F2F labels or scoring denominators. Hidden suites remain post-only
 * (never labeled F2P).
 */
@Service
public class FlakeQuarantineService {

    private final FlakeQuarantineRepository repository;
    private final OrgAccessService orgAccessService;

    public FlakeQuarantineService(
            FlakeQuarantineRepository repository,
            OrgAccessService orgAccessService) {
        this.repository = repository;
        this.orgAccessService = orgAccessService;
    }

    @Transactional(readOnly = true)
    public List<FlakeQuarantineDtos.Response> list(UUID orgId, boolean includeInactive) {
        orgAccessService.requireMembership(orgId, OrgRole.VIEWER);
        return repository.listAll(orgId, includeInactive).stream()
                .map(FlakeQuarantineService::toResponse)
                .toList();
    }

    @Transactional
    public FlakeQuarantineDtos.Response create(UUID orgId, FlakeQuarantineDtos.CreateRequest request) {
        Membership membership = orgAccessService.requireMembership(orgId, OrgRole.ADMIN);
        UUID suiteId = request.suiteId();
        String caseKey = normalizeCaseKey(request.caseKey());
        if (suiteId == null && caseKey == null) {
            throw ApiException.validation("Provide suiteId and/or caseKey for quarantine scope");
        }
        Instant now = Instant.now();
        Instant expiresAt = parseExpires(request.expiresAt());
        if (expiresAt != null && !expiresAt.isAfter(now)) {
            throw ApiException.validation("expiresAt must be in the future");
        }
        FlakeQuarantine row = new FlakeQuarantine(
                UUID.randomUUID(),
                orgId,
                suiteId,
                caseKey,
                request.reason() == null ? "" : request.reason().trim(),
                true,
                membership.userId(),
                now,
                now,
                expiresAt
        );
        try {
            return toResponse(repository.insert(row));
        } catch (DuplicateKeyException ex) {
            throw ApiException.conflict("Active quarantine already exists for this org/suite/case scope");
        }
    }

    @Transactional
    public void deactivate(UUID orgId, UUID quarantineId) {
        orgAccessService.requireMembership(orgId, OrgRole.ADMIN);
        FlakeQuarantine existing = repository.find(orgId, quarantineId)
                .orElseThrow(() -> ApiException.notFound("Flake quarantine not found"));
        if (!existing.active()) {
            return;
        }
        repository.deactivate(orgId, quarantineId);
    }

    /**
     * Resolve effective quarantined case keys for a suite execution.
     * When the suite itself is quarantined, {@code knownCaseKeys} are all marked.
     */
    public Set<String> resolveQuarantinedCaseKeys(
            UUID orgId,
            UUID suiteId,
            JsonNode suiteNode,
            JsonNode task,
            Iterable<String> knownCaseKeys) {
        Set<String> quarantined = new LinkedHashSet<>();
        Instant now = Instant.now();

        // Task-level explicit keys
        JsonNode configKeys = task == null
                ? null
                : task.path("evalConfig").path("quarantinedCaseKeys");
        if (configKeys != null && configKeys.isArray()) {
            for (JsonNode key : configKeys) {
                String value = normalizeCaseKey(key.asText(null));
                if (value != null) {
                    quarantined.add(value);
                }
            }
        }

        boolean suiteFlag = suiteNode != null && suiteNode.path("quarantined").asBoolean(false);
        if (suiteFlag) {
            for (String key : knownCaseKeys) {
                if (key != null && !key.isBlank()) {
                    quarantined.add(key);
                }
            }
        }

        List<FlakeQuarantine> rows = repository.listActive(orgId);
        boolean suiteLevelHit = false;
        for (FlakeQuarantine row : rows) {
            if (!row.isEffective(now)) {
                continue;
            }
            if (row.isSuiteLevel()) {
                if (suiteId != null && suiteId.equals(row.suiteId())) {
                    suiteLevelHit = true;
                }
                continue;
            }
            boolean suiteMatches = row.suiteId() == null || (suiteId != null && suiteId.equals(row.suiteId()));
            if (suiteMatches) {
                quarantined.add(row.caseKey());
            }
        }
        if (suiteLevelHit) {
            for (String key : knownCaseKeys) {
                if (key != null && !key.isBlank()) {
                    quarantined.add(key);
                }
            }
        }
        return quarantined;
    }

    public static boolean isQuarantinedMeta(String metaJson) {
        if (metaJson == null || metaJson.isBlank()) {
            return false;
        }
        String lower = metaJson.toLowerCase(Locale.ROOT);
        return lower.contains("\"quarantined\":true") || lower.contains("\"quarantined\": true");
    }

    public static String quarantineMeta(boolean quarantined, boolean flaky, boolean hidden) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        if (quarantined) {
            sb.append("\"quarantined\":true");
            first = false;
        }
        if (flaky) {
            if (!first) {
                sb.append(',');
            }
            sb.append("\"flaky\":true");
            first = false;
        }
        if (hidden) {
            if (!first) {
                sb.append(',');
            }
            sb.append("\"hidden\":true,\"neverF2P\":true");
            first = false;
        }
        if (first) {
            return "{}";
        }
        sb.append('}');
        return sb.toString();
    }

    private static Instant parseExpires(String expiresAt) {
        if (expiresAt == null || expiresAt.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(expiresAt.trim());
        } catch (Exception ex) {
            throw ApiException.validation("expiresAt must be an ISO-8601 instant");
        }
    }

    private static String normalizeCaseKey(String caseKey) {
        if (caseKey == null || caseKey.isBlank()) {
            return null;
        }
        return caseKey.trim();
    }

    private static FlakeQuarantineDtos.Response toResponse(FlakeQuarantine row) {
        return new FlakeQuarantineDtos.Response(
                row.id(),
                row.orgId(),
                row.suiteId(),
                row.caseKey(),
                row.isSuiteLevel() ? "SUITE" : "CASE",
                row.reason(),
                row.active(),
                row.createdByUserId(),
                row.createdAt(),
                row.updatedAt(),
                row.expiresAt()
        );
    }
}
