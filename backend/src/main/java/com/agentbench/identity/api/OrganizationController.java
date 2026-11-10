package com.agentbench.identity.api;

import com.agentbench.identity.api.dto.OrgDtos;
import com.agentbench.identity.api.dto.SamlIdpDtos;
import com.agentbench.identity.security.OrgAccessService;
import com.agentbench.identity.service.OrganizationService;
import com.agentbench.identity.service.SamlIdpAdminService;
import com.agentbench.identity.service.SecretService;
import com.agentbench.platform.api.ApiPaths;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping(ApiPaths.API_V1)
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "Organizations", description = "Orgs, memberships, encrypted secret refs, and SAML IdPs")
public class OrganizationController {

    private final OrganizationService organizationService;
    private final SecretService secretService;
    private final SamlIdpAdminService samlIdpAdminService;
    private final OrgAccessService orgAccessService;

    public OrganizationController(
            OrganizationService organizationService,
            SecretService secretService,
            SamlIdpAdminService samlIdpAdminService,
            OrgAccessService orgAccessService) {
        this.organizationService = organizationService;
        this.secretService = secretService;
        this.samlIdpAdminService = samlIdpAdminService;
        this.orgAccessService = orgAccessService;
    }

    @PostMapping(value = "/orgs", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create organization (caller becomes OWNER)")
    public OrgDtos.OrgResponse create(@Valid @RequestBody OrgDtos.CreateOrgRequest request) {
        return organizationService.create(orgAccessService.requirePrincipal(), request);
    }

    @GetMapping(value = "/orgs", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "List organizations for the current user")
    public List<OrgDtos.OrgResponse> listMine() {
        return organizationService.listMine(orgAccessService.requirePrincipal());
    }

    @GetMapping(value = "/orgs/{orgId}", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Get organization (members only)")
    public OrgDtos.OrgResponse get(@PathVariable UUID orgId) {
        return organizationService.get(orgId);
    }

    @GetMapping(value = "/orgs/{orgId}/members", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "List members")
    public List<OrgDtos.MemberResponse> members(@PathVariable UUID orgId) {
        return organizationService.listMembers(orgId);
    }

    @PostMapping(value = "/orgs/{orgId}/members", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Add member by email")
    public OrgDtos.MemberResponse addMember(
            @PathVariable UUID orgId,
            @Valid @RequestBody OrgDtos.AddMemberRequest request) {
        return organizationService.addMember(orgId, request);
    }

    @PutMapping(value = "/orgs/{orgId}/members/{userId}", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Update member role")
    public OrgDtos.MemberResponse updateMember(
            @PathVariable UUID orgId,
            @PathVariable UUID userId,
            @Valid @RequestBody OrgDtos.UpdateMemberRequest request) {
        return organizationService.updateMember(orgId, userId, request);
    }

    @DeleteMapping("/orgs/{orgId}/members/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Remove member")
    public void removeMember(@PathVariable UUID orgId, @PathVariable UUID userId) {
        organizationService.removeMember(orgId, userId);
    }

    @PostMapping(value = "/orgs/{orgId}/secrets", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create encrypted secret (plaintext write-only)")
    public OrgDtos.SecretResponse createSecret(
            @PathVariable UUID orgId,
            @Valid @RequestBody OrgDtos.CreateSecretRequest request) {
        return secretService.create(orgId, request);
    }

    @GetMapping(value = "/orgs/{orgId}/secrets", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "List secret metadata (never ciphertext/plaintext)")
    public List<OrgDtos.SecretResponse> listSecrets(@PathVariable UUID orgId) {
        return secretService.list(orgId);
    }

    @PostMapping(value = "/orgs/{orgId}/secrets/{secretId}/rotate", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Rotate secret plaintext")
    public OrgDtos.SecretResponse rotateSecret(
            @PathVariable UUID orgId,
            @PathVariable UUID secretId,
            @Valid @RequestBody OrgDtos.RotateSecretRequest request) {
        return secretService.rotate(orgId, secretId, request);
    }

    @DeleteMapping("/orgs/{orgId}/secrets/{secretId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Delete secret")
    public void deleteSecret(@PathVariable UUID orgId, @PathVariable UUID secretId) {
        secretService.delete(orgId, secretId);
    }

    @GetMapping(value = "/orgs/{orgId}/saml/idps", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "List org SAML IdP configs (OWNER/ADMIN)")
    public List<SamlIdpDtos.IdpResponse> listSamlIdps(@PathVariable UUID orgId) {
        return samlIdpAdminService.list(orgId);
    }

    @PostMapping(
            value = "/orgs/{orgId}/saml/idps",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Add a SAML IdP (OWNER/ADMIN)")
    public SamlIdpDtos.IdpResponse createSamlIdp(
            @PathVariable UUID orgId,
            @Valid @RequestBody SamlIdpDtos.CreateRequest request) {
        return samlIdpAdminService.create(orgId, request);
    }

    @PutMapping(
            value = "/orgs/{orgId}/saml/idps/{idpId}",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Update a SAML IdP (OWNER/ADMIN)")
    public SamlIdpDtos.IdpResponse updateSamlIdp(
            @PathVariable UUID orgId,
            @PathVariable UUID idpId,
            @Valid @RequestBody SamlIdpDtos.UpdateRequest request) {
        return samlIdpAdminService.update(orgId, idpId, request);
    }

    @DeleteMapping("/orgs/{orgId}/saml/idps/{idpId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Delete a SAML IdP (OWNER/ADMIN)")
    public void deleteSamlIdp(@PathVariable UUID orgId, @PathVariable UUID idpId) {
        samlIdpAdminService.delete(orgId, idpId);
    }
}
