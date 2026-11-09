package com.agentbench.identity.service;

import com.agentbench.identity.api.dto.OrgDtos;
import com.agentbench.identity.domain.OrgRole;
import com.agentbench.identity.domain.SecretRef;
import com.agentbench.identity.infra.SecretRefRepository;
import com.agentbench.identity.security.OrgAccessService;
import com.agentbench.platform.crypto.SecretEncryptionService;
import com.agentbench.platform.error.ApiException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class SecretService {

    private final SecretRefRepository secretRefRepository;
    private final SecretEncryptionService encryptionService;
    private final OrgAccessService orgAccessService;

    public SecretService(
            SecretRefRepository secretRefRepository,
            SecretEncryptionService encryptionService,
            OrgAccessService orgAccessService) {
        this.secretRefRepository = secretRefRepository;
        this.encryptionService = encryptionService;
        this.orgAccessService = orgAccessService;
    }

    @Transactional
    public OrgDtos.SecretResponse create(UUID orgId, OrgDtos.CreateSecretRequest request) {
        orgAccessService.requireMembership(orgId, OrgRole.DEVELOPER);
        byte[] ciphertext = encryptionService.encrypt(request.plaintext());
        try {
            SecretRef ref = secretRefRepository.insert(
                    orgId,
                    request.name().trim(),
                    ciphertext,
                    SecretEncryptionService.CURRENT_KEY_VERSION,
                    request.purpose().trim().toUpperCase()
            );
            return toResponse(ref);
        } catch (DuplicateKeyException ex) {
            throw ApiException.conflict("Secret name already exists in organization");
        }
    }

    @Transactional(readOnly = true)
    public List<OrgDtos.SecretResponse> list(UUID orgId) {
        orgAccessService.requireMembership(orgId, OrgRole.DEVELOPER);
        return secretRefRepository.listByOrg(orgId).stream()
                .map(SecretService::toResponse)
                .toList();
    }

    @Transactional
    public OrgDtos.SecretResponse rotate(UUID orgId, UUID secretId, OrgDtos.RotateSecretRequest request) {
        orgAccessService.requireMembership(orgId, OrgRole.DEVELOPER);
        SecretRef existing = secretRefRepository.findById(orgId, secretId)
                .orElseThrow(() -> ApiException.notFound("Secret not found"));
        byte[] ciphertext = encryptionService.encrypt(request.plaintext());
        secretRefRepository.rotate(orgId, secretId, ciphertext, SecretEncryptionService.CURRENT_KEY_VERSION);
        return secretRefRepository.findById(orgId, secretId)
                .map(SecretService::toResponse)
                .orElse(toResponse(existing));
    }

    @Transactional
    public void delete(UUID orgId, UUID secretId) {
        orgAccessService.requireMembership(orgId, OrgRole.ADMIN);
        secretRefRepository.findById(orgId, secretId)
                .orElseThrow(() -> ApiException.notFound("Secret not found"));
        secretRefRepository.delete(orgId, secretId);
    }

    /** Internal decrypt for trusted server-side use (repo validation). Never expose via API. */
    @Transactional(readOnly = true)
    public String decryptForOrg(UUID orgId, UUID secretId) {
        SecretRef ref = secretRefRepository.findById(orgId, secretId)
                .orElseThrow(() -> ApiException.notFound("Secret not found"));
        return encryptionService.decrypt(ref.ciphertext());
    }

    private static OrgDtos.SecretResponse toResponse(SecretRef ref) {
        // Metadata only — never ciphertext or plaintext.
        return new OrgDtos.SecretResponse(ref.id(), ref.name(), ref.purpose(), ref.createdAt(), ref.rotatedAt());
    }
}
