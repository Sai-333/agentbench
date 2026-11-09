package com.agentbench.identity.service;

import com.agentbench.identity.api.dto.OrgDtos;
import com.agentbench.identity.domain.OrgRole;
import com.agentbench.identity.infra.OrganizationRepository;
import com.agentbench.identity.infra.UserRepository;
import com.agentbench.identity.security.OrgAccessService;
import com.agentbench.identity.security.UserPrincipal;
import com.agentbench.platform.error.ApiException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
public class OrganizationService {

    private final OrganizationRepository organizationRepository;
    private final UserRepository userRepository;
    private final OrgAccessService orgAccessService;

    public OrganizationService(
            OrganizationRepository organizationRepository,
            UserRepository userRepository,
            OrgAccessService orgAccessService) {
        this.organizationRepository = organizationRepository;
        this.userRepository = userRepository;
        this.orgAccessService = orgAccessService;
    }

    @Transactional
    public OrgDtos.OrgResponse create(UserPrincipal principal, OrgDtos.CreateOrgRequest request) {
        String slug = request.slug().trim().toLowerCase(Locale.ROOT);
        if (organizationRepository.findBySlug(slug).isPresent()) {
            throw ApiException.conflict("Organization slug already exists");
        }
        try {
            var org = organizationRepository.insert(request.name().trim(), slug);
            organizationRepository.insertMembership(org.id(), principal.userId(), OrgRole.OWNER);
            return toResponse(org);
        } catch (DuplicateKeyException ex) {
            throw ApiException.conflict("Organization slug already exists");
        }
    }

    @Transactional(readOnly = true)
    public List<OrgDtos.OrgResponse> listMine(UserPrincipal principal) {
        return organizationRepository.findForUser(principal.userId()).stream()
                .map(OrganizationService::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public OrgDtos.OrgResponse get(UUID orgId) {
        orgAccessService.requireMembership(orgId, OrgRole.VIEWER);
        var org = organizationRepository.findById(orgId)
                .orElseThrow(() -> ApiException.notFound("Organization not found"));
        return toResponse(org);
    }

    @Transactional(readOnly = true)
    public List<OrgDtos.MemberResponse> listMembers(UUID orgId) {
        orgAccessService.requireMembership(orgId, OrgRole.VIEWER);
        return organizationRepository.listMemberships(orgId).stream()
                .map(membership -> {
                    var user = userRepository.findById(membership.userId())
                            .orElseThrow(() -> ApiException.notFound("User not found"));
                    return new OrgDtos.MemberResponse(
                            user.id(),
                            user.email(),
                            user.displayName(),
                            membership.role().name(),
                            membership.createdAt()
                    );
                })
                .toList();
    }

    @Transactional
    public OrgDtos.MemberResponse addMember(UUID orgId, OrgDtos.AddMemberRequest request) {
        orgAccessService.requireMembership(orgId, OrgRole.ADMIN);
        OrgRole role = OrgRole.fromString(request.role());
        if (role == OrgRole.OWNER) {
            throw ApiException.validation("Cannot assign OWNER via add-member; transfer ownership separately");
        }
        var user = userRepository.findByEmail(AuthService.normalizeEmail(request.email()))
                .orElseThrow(() -> ApiException.notFound("User not found for email"));
        if (organizationRepository.findMembership(orgId, user.id()).isPresent()) {
            throw ApiException.conflict("User is already a member");
        }
        var membership = organizationRepository.insertMembership(orgId, user.id(), role);
        return new OrgDtos.MemberResponse(user.id(), user.email(), user.displayName(), role.name(), membership.createdAt());
    }

    @Transactional
    public OrgDtos.MemberResponse updateMember(UUID orgId, UUID userId, OrgDtos.UpdateMemberRequest request) {
        var actor = orgAccessService.requireMembership(orgId, OrgRole.ADMIN);
        OrgRole newRole = OrgRole.fromString(request.role());
        var target = organizationRepository.findMembership(orgId, userId)
                .orElseThrow(() -> ApiException.notFound("Membership not found"));

        if (target.role() == OrgRole.OWNER && newRole != OrgRole.OWNER) {
            if (actor.role() != OrgRole.OWNER) {
                throw ApiException.forbidden("Only an OWNER can demote an OWNER");
            }
            if (organizationRepository.countOwners(orgId) <= 1) {
                throw ApiException.conflict("Organization must retain at least one OWNER");
            }
        }
        if (newRole == OrgRole.OWNER && actor.role() != OrgRole.OWNER) {
            throw ApiException.forbidden("Only an OWNER can promote to OWNER");
        }

        organizationRepository.updateMembershipRole(orgId, userId, newRole);
        var user = userRepository.findById(userId).orElseThrow(() -> ApiException.notFound("User not found"));
        return new OrgDtos.MemberResponse(user.id(), user.email(), user.displayName(), newRole.name(), target.createdAt());
    }

    @Transactional
    public void removeMember(UUID orgId, UUID userId) {
        var actor = orgAccessService.requireMembership(orgId, OrgRole.ADMIN);
        var target = organizationRepository.findMembership(orgId, userId)
                .orElseThrow(() -> ApiException.notFound("Membership not found"));
        if (target.role() == OrgRole.OWNER) {
            if (actor.role() != OrgRole.OWNER) {
                throw ApiException.forbidden("Only an OWNER can remove an OWNER");
            }
            if (organizationRepository.countOwners(orgId) <= 1) {
                throw ApiException.conflict("Organization must retain at least one OWNER");
            }
        }
        organizationRepository.deleteMembership(orgId, userId);
    }

    private static OrgDtos.OrgResponse toResponse(com.agentbench.identity.domain.Organization org) {
        return new OrgDtos.OrgResponse(org.id(), org.name(), org.slug(), org.createdAt());
    }
}
