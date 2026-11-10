package com.agentbench.platform.audit;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Append-only WORM audit API. Inserts go through DB hash-chain triggers;
 * UPDATE/DELETE are denied at the database for {@code agentbench_app}.
 */
@Service
public class WormAuditService {

    private final WormAuditRepository repository;
    private final NamedParameterJdbcTemplate jdbc;

    public WormAuditService(WormAuditRepository repository, NamedParameterJdbcTemplate jdbc) {
        this.repository = repository;
        this.jdbc = jdbc;
    }

    @Transactional
    public WormAuditEntry append(
            UUID orgId,
            String eventType,
            String actor,
            String resourceType,
            String resourceId,
            String payloadJson) {
        return repository.append(orgId, eventType, Instant.now(), actor, resourceType, resourceId, payloadJson);
    }

    @Transactional(readOnly = true)
    public List<WormAuditEntry> listByOrg(UUID orgId, int limit) {
        return repository.listByOrg(orgId, limit);
    }

    /**
     * Recomputes hashes with the same SQL functions used at insert time.
     * Returns empty when the per-org chain is intact; otherwise the first broken row id.
     */
    @Transactional(readOnly = true)
    public ChainVerification verifyChain(UUID orgId) {
        Long hashMismatch = jdbc.query("""
                        SELECT e.id
                        FROM platform.worm_audit_events e
                        WHERE e.org_id IS NOT DISTINCT FROM :orgId
                          AND e.entry_hash <> platform.worm_audit_hash(platform.worm_audit_canonical(e))
                        ORDER BY e.id
                        LIMIT 1
                        """,
                new MapSqlParameterSource("orgId", orgId),
                rs -> rs.next() ? rs.getLong(1) : null);

        if (hashMismatch != null) {
            return new ChainVerification(false, hashMismatch, "entry_hash mismatch");
        }

        Long linkBreak = jdbc.query("""
                        SELECT broken.id
                        FROM (
                            SELECT e.id,
                                   e.prev_hash,
                                   lag(e.entry_hash) OVER (ORDER BY e.id) AS expected_prev
                            FROM platform.worm_audit_events e
                            WHERE e.org_id IS NOT DISTINCT FROM :orgId
                        ) broken
                        WHERE (broken.expected_prev IS NULL AND broken.prev_hash <> repeat('0', 64))
                           OR (broken.expected_prev IS NOT NULL AND broken.prev_hash <> broken.expected_prev)
                        ORDER BY broken.id
                        LIMIT 1
                        """,
                new MapSqlParameterSource("orgId", orgId),
                rs -> rs.next() ? rs.getLong(1) : null);

        if (linkBreak != null) {
            return new ChainVerification(false, linkBreak, "prev_hash chain break");
        }

        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM platform.worm_audit_events
                WHERE org_id IS NOT DISTINCT FROM :orgId
                """,
                new MapSqlParameterSource("orgId", orgId),
                Integer.class);
        return new ChainVerification(true, null, "ok (" + (count == null ? 0 : count) + " entries)");
    }

    public record ChainVerification(boolean intact, Long brokenId, String detail) {
    }
}
