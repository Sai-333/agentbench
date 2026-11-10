package com.agentbench.identity.infra;

import com.agentbench.identity.domain.SamlIdentityProvider;
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
public class SamlIdentityProviderRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public SamlIdentityProviderRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public SamlIdentityProvider insert(
            UUID orgId,
            String registrationId,
            String displayName,
            String metadataUrl,
            String metadataXml,
            String attrEmail,
            String attrDisplayName,
            boolean enabled) {
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        jdbc.update("""
                INSERT INTO identity.saml_identity_providers
                    (id, org_id, registration_id, display_name, metadata_url, metadata_xml,
                     attr_email, attr_display_name, enabled, created_at, updated_at)
                VALUES
                    (:id, :orgId, :registrationId, :displayName, :metadataUrl, :metadataXml,
                     :attrEmail, :attrDisplayName, :enabled, :createdAt, :updatedAt)
                """,
                new MapSqlParameterSource()
                        .addValue("id", id)
                        .addValue("orgId", orgId)
                        .addValue("registrationId", registrationId)
                        .addValue("displayName", displayName)
                        .addValue("metadataUrl", blankToNull(metadataUrl))
                        .addValue("metadataXml", blankToNull(metadataXml))
                        .addValue("attrEmail", attrEmail)
                        .addValue("attrDisplayName", attrDisplayName)
                        .addValue("enabled", enabled)
                        .addValue("createdAt", Timestamp.from(now))
                        .addValue("updatedAt", Timestamp.from(now)));
        return new SamlIdentityProvider(
                id, orgId, registrationId, displayName,
                blankToNull(metadataUrl), blankToNull(metadataXml),
                attrEmail, attrDisplayName, enabled, now, now
        );
    }

    public Optional<SamlIdentityProvider> findById(UUID orgId, UUID idpId) {
        var list = jdbc.query("""
                SELECT id, org_id, registration_id, display_name, metadata_url, metadata_xml,
                       attr_email, attr_display_name, enabled, created_at, updated_at
                FROM identity.saml_identity_providers
                WHERE org_id = :orgId AND id = :id
                """,
                new MapSqlParameterSource()
                        .addValue("orgId", orgId)
                        .addValue("id", idpId),
                (rs, rowNum) -> mapRow(rs));
        return list.stream().findFirst();
    }

    public Optional<SamlIdentityProvider> findByRegistrationId(String registrationId) {
        var list = jdbc.query("""
                SELECT id, org_id, registration_id, display_name, metadata_url, metadata_xml,
                       attr_email, attr_display_name, enabled, created_at, updated_at
                FROM identity.saml_identity_providers
                WHERE registration_id = :registrationId
                """,
                new MapSqlParameterSource("registrationId", registrationId),
                (rs, rowNum) -> mapRow(rs));
        return list.stream().findFirst();
    }

    public List<SamlIdentityProvider> listByOrg(UUID orgId) {
        return jdbc.query("""
                SELECT id, org_id, registration_id, display_name, metadata_url, metadata_xml,
                       attr_email, attr_display_name, enabled, created_at, updated_at
                FROM identity.saml_identity_providers
                WHERE org_id = :orgId
                ORDER BY display_name, registration_id
                """,
                new MapSqlParameterSource("orgId", orgId),
                (rs, rowNum) -> mapRow(rs));
    }

    public List<SamlIdentityProvider> listEnabled() {
        return jdbc.query("""
                SELECT id, org_id, registration_id, display_name, metadata_url, metadata_xml,
                       attr_email, attr_display_name, enabled, created_at, updated_at
                FROM identity.saml_identity_providers
                WHERE enabled = TRUE
                ORDER BY display_name, registration_id
                """,
                (rs, rowNum) -> mapRow(rs));
    }

    public Optional<SamlIdentityProvider> update(
            UUID orgId,
            UUID idpId,
            String displayName,
            String metadataUrl,
            String metadataXml,
            String attrEmail,
            String attrDisplayName,
            boolean enabled) {
        Instant now = Instant.now();
        int updated = jdbc.update("""
                UPDATE identity.saml_identity_providers
                SET display_name = :displayName,
                    metadata_url = :metadataUrl,
                    metadata_xml = :metadataXml,
                    attr_email = :attrEmail,
                    attr_display_name = :attrDisplayName,
                    enabled = :enabled,
                    updated_at = :updatedAt
                WHERE org_id = :orgId AND id = :id
                """,
                new MapSqlParameterSource()
                        .addValue("orgId", orgId)
                        .addValue("id", idpId)
                        .addValue("displayName", displayName)
                        .addValue("metadataUrl", blankToNull(metadataUrl))
                        .addValue("metadataXml", blankToNull(metadataXml))
                        .addValue("attrEmail", attrEmail)
                        .addValue("attrDisplayName", attrDisplayName)
                        .addValue("enabled", enabled)
                        .addValue("updatedAt", Timestamp.from(now)));
        if (updated == 0) {
            return Optional.empty();
        }
        return findById(orgId, idpId);
    }

    public boolean delete(UUID orgId, UUID idpId) {
        int deleted = jdbc.update("""
                DELETE FROM identity.saml_identity_providers
                WHERE org_id = :orgId AND id = :id
                """,
                new MapSqlParameterSource()
                        .addValue("orgId", orgId)
                        .addValue("id", idpId));
        return deleted > 0;
    }

    private static SamlIdentityProvider mapRow(ResultSet rs) throws SQLException {
        Timestamp created = rs.getTimestamp("created_at");
        Timestamp updated = rs.getTimestamp("updated_at");
        return new SamlIdentityProvider(
                rs.getObject("id", UUID.class),
                rs.getObject("org_id", UUID.class),
                rs.getString("registration_id"),
                rs.getString("display_name"),
                rs.getString("metadata_url"),
                rs.getString("metadata_xml"),
                rs.getString("attr_email"),
                rs.getString("attr_display_name"),
                rs.getBoolean("enabled"),
                created.toInstant(),
                updated.toInstant()
        );
    }

    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value;
    }
}
