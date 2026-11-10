package com.agentbench.identity.service;

import com.agentbench.identity.api.dto.SamlIdpDtos;
import com.agentbench.identity.auth.SamlProperties;
import com.agentbench.identity.auth.SamlRegistrationFactory;
import com.agentbench.identity.domain.OrgRole;
import com.agentbench.identity.domain.SamlIdentityProvider;
import com.agentbench.identity.infra.SamlIdentityProviderRepository;
import com.agentbench.identity.security.OrgAccessService;
import com.agentbench.platform.error.ApiException;
import com.agentbench.platform.rls.RlsContext;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * OWNER/ADMIN CRUD for org-scoped SAML IdP configurations.
 */
@Service
public class SamlIdpAdminService {

    private final SamlIdentityProviderRepository repository;
    private final OrgAccessService orgAccessService;
    private final SamlProperties properties;
    private final SamlRegistrationFactory registrationFactory;

    public SamlIdpAdminService(
            SamlIdentityProviderRepository repository,
            OrgAccessService orgAccessService,
            SamlProperties properties,
            SamlRegistrationFactory registrationFactory) {
        this.repository = repository;
        this.orgAccessService = orgAccessService;
        this.properties = properties;
        this.registrationFactory = registrationFactory;
    }

    @Transactional(readOnly = true)
    public List<SamlIdpDtos.IdpResponse> list(UUID orgId) {
        orgAccessService.requireMembership(orgId, OrgRole.ADMIN);
        return repository.listByOrg(orgId).stream().map(SamlIdpAdminService::toResponse).toList();
    }

    @Transactional
    public SamlIdpDtos.IdpResponse create(UUID orgId, SamlIdpDtos.CreateRequest request) {
        orgAccessService.requireMembership(orgId, OrgRole.ADMIN);
        requireSpReady();
        String registrationId = request.registrationId().trim().toLowerCase(Locale.ROOT);
        if (registrationId.equalsIgnoreCase(properties.getRegistrationId())) {
            throw ApiException.conflict("registrationId reserved for env/props fallback IdP");
        }
        String metadataUrl = normalizeOptional(request.metadataUrl());
        String metadataXml = normalizeOptional(request.metadataXml());
        requireMetadata(metadataUrl, metadataXml);
        boolean enabled = request.enabled() == null || Boolean.TRUE.equals(request.enabled());
        String attrEmail = blankOrDefault(request.attrEmail(), "email");
        String attrDisplayName = blankOrDefault(request.attrDisplayName(), "displayName");
        try {
            // Global uniqueness check across orgs (registration_id UNIQUE).
            boolean taken = Boolean.TRUE.equals(RlsContext.callWithBypass(
                    () -> repository.findByRegistrationId(registrationId).isPresent()));
            if (taken) {
                throw ApiException.conflict("SAML registrationId already exists");
            }
            SamlIdentityProvider created = repository.insert(
                    orgId,
                    registrationId,
                    request.displayName().trim(),
                    metadataUrl,
                    metadataXml,
                    attrEmail,
                    attrDisplayName,
                    enabled
            );
            validateMetadataLoadable(created);
            return toResponse(created);
        } catch (DuplicateKeyException ex) {
            throw ApiException.conflict("SAML registrationId already exists");
        }
    }

    @Transactional
    public SamlIdpDtos.IdpResponse update(UUID orgId, UUID idpId, SamlIdpDtos.UpdateRequest request) {
        orgAccessService.requireMembership(orgId, OrgRole.ADMIN);
        requireSpReady();
        SamlIdentityProvider existing = repository.findById(orgId, idpId)
                .orElseThrow(() -> ApiException.notFound("SAML IdP not found"));
        String metadataUrl = normalizeOptional(request.metadataUrl());
        String metadataXml = normalizeOptional(request.metadataXml());
        // Allow omitting XML on update when URL is set, or keep previous XML if both blank but we need one.
        if (metadataUrl == null && metadataXml == null) {
            metadataUrl = existing.metadataUrl();
            metadataXml = existing.metadataXml();
        } else if (metadataUrl != null && metadataXml == null) {
            // URL-only update clears stored XML
            metadataXml = null;
        } else if (metadataXml != null && metadataUrl == null) {
            metadataUrl = null;
        }
        requireMetadata(metadataUrl, metadataXml);
        String attrEmail = blankOrDefault(request.attrEmail(), existing.attrEmail());
        String attrDisplayName = blankOrDefault(request.attrDisplayName(), existing.attrDisplayName());
        SamlIdentityProvider updated = repository.update(
                orgId,
                idpId,
                request.displayName().trim(),
                metadataUrl,
                metadataXml,
                attrEmail,
                attrDisplayName,
                request.enabled()
        ).orElseThrow(() -> ApiException.notFound("SAML IdP not found"));
        registrationFactory.evict(updated.registrationId());
        validateMetadataLoadable(updated);
        return toResponse(updated);
    }

    @Transactional
    public void delete(UUID orgId, UUID idpId) {
        orgAccessService.requireMembership(orgId, OrgRole.ADMIN);
        SamlIdentityProvider existing = repository.findById(orgId, idpId)
                .orElseThrow(() -> ApiException.notFound("SAML IdP not found"));
        repository.delete(orgId, idpId);
        registrationFactory.evict(existing.registrationId());
    }

    /** Public login listing — enabled DB IdPs across orgs (controlled RLS bypass). */
    @Transactional(readOnly = true)
    public List<SamlIdentityProvider> listEnabledForLogin() {
        if (!properties.isSpReady()) {
            return List.of();
        }
        return RlsContext.callWithBypass(repository::listEnabled);
    }

    @Transactional(readOnly = true)
    public SamlIdentityProvider requireEnabledByRegistrationId(String registrationId) {
        requireSpReady();
        SamlIdentityProvider idp = RlsContext.callWithBypass(
                () -> repository.findByRegistrationId(registrationId.trim().toLowerCase(Locale.ROOT))
                        .orElseThrow(() -> ApiException.notFound("SAML IdP not found")));
        if (!idp.enabled()) {
            throw ApiException.validation("SAML IdP is disabled");
        }
        return idp;
    }

    private void validateMetadataLoadable(SamlIdentityProvider idp) {
        try {
            registrationFactory.evict(idp.registrationId());
            registrationFactory.requireRegistration(
                    idp.registrationId(),
                    idp.metadataUrl(),
                    idp.metadataXml()
            );
        } catch (ApiException ex) {
            throw ex;
        } catch (Exception ex) {
            throw ApiException.validation("Unable to load IdP metadata: " + ex.getMessage());
        }
    }

    private void requireSpReady() {
        if (!properties.isSpReady()) {
            throw ApiException.validation(
                    "SAML SP is disabled; set AGENTBENCH_SAML_ENABLED=true with entity-id and acs-url");
        }
    }

    private static void requireMetadata(String metadataUrl, String metadataXml) {
        boolean hasUrl = metadataUrl != null && !metadataUrl.isBlank();
        boolean hasXml = metadataXml != null && !metadataXml.isBlank();
        if (!hasUrl && !hasXml) {
            throw ApiException.validation("metadataUrl or metadataXml is required");
        }
    }

    private static String normalizeOptional(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private static String blankOrDefault(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.trim();
    }

    private static SamlIdpDtos.IdpResponse toResponse(SamlIdentityProvider idp) {
        return new SamlIdpDtos.IdpResponse(
                idp.id(),
                idp.orgId(),
                idp.registrationId(),
                idp.displayName(),
                idp.metadataUrl(),
                idp.metadataXml() != null && !idp.metadataXml().isBlank(),
                idp.attrEmail(),
                idp.attrDisplayName(),
                idp.enabled(),
                idp.createdAt(),
                idp.updatedAt()
        );
    }
}
