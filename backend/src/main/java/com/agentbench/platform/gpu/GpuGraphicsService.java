package com.agentbench.platform.gpu;

import com.agentbench.identity.domain.OrgRole;
import com.agentbench.identity.infra.OrganizationRepository;
import com.agentbench.identity.security.OrgAccessService;
import com.agentbench.identity.security.UserPrincipal;
import com.agentbench.platform.error.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Platform-scoped graphics probe. The probe runs outside a database transaction.
 * A missing GPU is stored as unavailable with a null score.
 */
@Service
public class GpuGraphicsService {

    private static final Logger log = LoggerFactory.getLogger(GpuGraphicsService.class);

    static final int DEFAULT_LIMIT = 20;
    static final int MAX_LIMIT = 50;

    private final GpuGraphicsRepository repository;
    private final GpuGraphicsProbe probe;
    private final OrgAccessService orgAccessService;
    private final OrganizationRepository organizationRepository;
    private final ReentrantLock runLock = new ReentrantLock();

    public GpuGraphicsService(
            GpuGraphicsRepository repository,
            GpuGraphicsProbe probe,
            OrgAccessService orgAccessService,
            OrganizationRepository organizationRepository) {
        this.repository = repository;
        this.probe = probe;
        this.orgAccessService = orgAccessService;
        this.organizationRepository = organizationRepository;
    }

    public GpuGraphicsDtos.Result run() {
        UserPrincipal principal = requireAdmin();
        if (!runLock.tryLock()) {
            throw ApiException.conflict("A graphics probe is already running");
        }
        try {
            GpuGraphicsResult result = probe.probe();
            Instant probedAt = Instant.now();
            UUID id = repository.insert(result, principal.userId(), probedAt);
            log.info("Stored GPU graphics status={} frames={} scored={}",
                    result.status(), result.frames(), result.score() != null);
            return repository.find(id).orElseThrow(() -> ApiException.notFound("Graphics probe result not found"));
        } finally {
            runLock.unlock();
        }
    }

    public List<GpuGraphicsDtos.Result> list(int limit) {
        requireAdmin();
        int bounded = Math.max(1, Math.min(limit, MAX_LIMIT));
        return repository.list(bounded);
    }

    public GpuGraphicsDtos.Result get(UUID id) {
        requireAdmin();
        return repository.find(id).orElseThrow(() -> ApiException.notFound("Graphics probe result not found"));
    }

    private UserPrincipal requireAdmin() {
        UserPrincipal principal = orgAccessService.requirePrincipal();
        if (!organizationRepository.hasRoleAtLeast(principal.userId(), OrgRole.ADMIN)) {
            throw ApiException.forbidden(
                    "Graphics probe results are platform-scoped and require OWNER or ADMIN on at least one organization");
        }
        return principal;
    }
}
