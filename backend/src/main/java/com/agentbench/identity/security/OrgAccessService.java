package com.agentbench.identity.security;

import com.agentbench.identity.domain.Membership;
import com.agentbench.identity.domain.OrgRole;
import com.agentbench.identity.infra.OrganizationRepository;
import com.agentbench.platform.error.ApiException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class OrgAccessService {

    private final OrganizationRepository organizationRepository;

    public OrgAccessService(OrganizationRepository organizationRepository) {
        this.organizationRepository = organizationRepository;
    }

    public UserPrincipal requirePrincipal() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof UserPrincipal principal)) {
            throw ApiException.unauthorized("Authentication required");
        }
        return principal;
    }

    public Membership requireMembership(UUID orgId, OrgRole minimumRole) {
        UserPrincipal principal = requirePrincipal();
        // Never trust client orgId alone — membership must exist for the authenticated user.
        Membership membership = organizationRepository.findMembership(orgId, principal.userId())
                .orElseThrow(() -> ApiException.forbidden("Not a member of this organization"));
        if (!membership.role().atLeast(minimumRole)) {
            throw ApiException.forbidden("Insufficient role for this operation");
        }
        organizationRepository.findById(orgId)
                .orElseThrow(() -> ApiException.notFound("Organization not found"));
        return membership;
    }
}
