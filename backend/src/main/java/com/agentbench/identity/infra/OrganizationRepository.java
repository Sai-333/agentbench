package com.agentbench.identity.infra;

import com.agentbench.identity.domain.Membership;
import com.agentbench.identity.domain.OrgRole;
import com.agentbench.identity.domain.Organization;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class OrganizationRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public OrganizationRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Organization insert(String name, String slug) {
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        jdbc.update("""
                INSERT INTO identity.organizations (id, name, slug, created_at, updated_at)
                VALUES (:id, :name, :slug, :createdAt, :updatedAt)
                """,
                new MapSqlParameterSource()
                        .addValue("id", id)
                        .addValue("name", name)
                        .addValue("slug", slug)
                        .addValue("createdAt", Timestamp.from(now))
                        .addValue("updatedAt", Timestamp.from(now)));
        return new Organization(id, name, slug, now, now);
    }

    public Optional<Organization> findById(UUID id) {
        var list = jdbc.query("""
                SELECT id, name, slug, created_at, updated_at
                FROM identity.organizations WHERE id = :id
                """,
                new MapSqlParameterSource("id", id),
                (rs, rowNum) -> mapOrg(rs));
        return list.stream().findFirst();
    }

    public Optional<Organization> findBySlug(String slug) {
        var list = jdbc.query("""
                SELECT id, name, slug, created_at, updated_at
                FROM identity.organizations WHERE slug = :slug
                """,
                new MapSqlParameterSource("slug", slug),
                (rs, rowNum) -> mapOrg(rs));
        return list.stream().findFirst();
    }

    public List<Organization> findForUser(UUID userId) {
        return jdbc.query("""
                SELECT o.id, o.name, o.slug, o.created_at, o.updated_at
                FROM identity.organizations o
                INNER JOIN identity.memberships m ON m.org_id = o.id
                WHERE m.user_id = :userId
                ORDER BY o.name
                """,
                new MapSqlParameterSource("userId", userId),
                (rs, rowNum) -> mapOrg(rs));
    }

    public Membership insertMembership(UUID orgId, UUID userId, OrgRole role) {
        Instant now = Instant.now();
        jdbc.update("""
                INSERT INTO identity.memberships (org_id, user_id, role, created_at, updated_at)
                VALUES (:orgId, :userId, :role, :createdAt, :updatedAt)
                """,
                new MapSqlParameterSource()
                        .addValue("orgId", orgId)
                        .addValue("userId", userId)
                        .addValue("role", role.name())
                        .addValue("createdAt", Timestamp.from(now))
                        .addValue("updatedAt", Timestamp.from(now)));
        return new Membership(orgId, userId, role, now, now);
    }

    public Optional<Membership> findMembership(UUID orgId, UUID userId) {
        var list = jdbc.query("""
                SELECT org_id, user_id, role, created_at, updated_at
                FROM identity.memberships
                WHERE org_id = :orgId AND user_id = :userId
                """,
                new MapSqlParameterSource()
                        .addValue("orgId", orgId)
                        .addValue("userId", userId),
                (rs, rowNum) -> mapMembership(rs));
        return list.stream().findFirst();
    }

    public List<Membership> listMemberships(UUID orgId) {
        return jdbc.query("""
                SELECT org_id, user_id, role, created_at, updated_at
                FROM identity.memberships
                WHERE org_id = :orgId
                ORDER BY role, created_at
                """,
                new MapSqlParameterSource("orgId", orgId),
                (rs, rowNum) -> mapMembership(rs));
    }

    public void updateMembershipRole(UUID orgId, UUID userId, OrgRole role) {
        jdbc.update("""
                UPDATE identity.memberships
                SET role = :role, updated_at = :updatedAt
                WHERE org_id = :orgId AND user_id = :userId
                """,
                new MapSqlParameterSource()
                        .addValue("orgId", orgId)
                        .addValue("userId", userId)
                        .addValue("role", role.name())
                        .addValue("updatedAt", Timestamp.from(Instant.now())));
    }

    public void deleteMembership(UUID orgId, UUID userId) {
        jdbc.update("""
                DELETE FROM identity.memberships
                WHERE org_id = :orgId AND user_id = :userId
                """,
                new MapSqlParameterSource()
                        .addValue("orgId", orgId)
                        .addValue("userId", userId));
    }

    public long countOwners(UUID orgId) {
        Long count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM identity.memberships
                WHERE org_id = :orgId AND role = 'OWNER'
                """,
                new MapSqlParameterSource("orgId", orgId),
                Long.class);
        return count == null ? 0 : count;
    }

    private static Organization mapOrg(ResultSet rs) throws SQLException {
        return new Organization(
                rs.getObject("id", UUID.class),
                rs.getString("name"),
                rs.getString("slug"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant()
        );
    }

    private static Membership mapMembership(ResultSet rs) throws SQLException {
        return new Membership(
                rs.getObject("org_id", UUID.class),
                rs.getObject("user_id", UUID.class),
                OrgRole.fromString(rs.getString("role")),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant()
        );
    }
}
