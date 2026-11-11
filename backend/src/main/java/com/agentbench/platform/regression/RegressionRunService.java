package com.agentbench.platform.regression;

import com.agentbench.identity.domain.OrgRole;
import com.agentbench.identity.infra.OrganizationRepository;
import com.agentbench.identity.security.OrgAccessService;
import com.agentbench.identity.security.UserPrincipal;
import com.agentbench.platform.error.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Platform-scoped regression runs. Callers must be authenticated and hold
 * OWNER or ADMIN on at least one organization. Runs are not stored under an org.
 */
@Service
public class RegressionRunService {

    static final int MAX_BODY_CHARS = 2_000_000;
    static final int DEFAULT_LIMIT = 50;
    static final int MAX_LIMIT = 100;

    private final RegressionRunRepository repository;
    private final OrgAccessService orgAccessService;
    private final OrganizationRepository organizationRepository;

    public RegressionRunService(
            RegressionRunRepository repository,
            OrgAccessService orgAccessService,
            OrganizationRepository organizationRepository) {
        this.repository = repository;
        this.orgAccessService = orgAccessService;
        this.organizationRepository = organizationRepository;
    }

    public record IngestCommand(
            String contentType,
            String body,
            String gitRepo,
            String gitBranch,
            String gitSha,
            String suiteName,
            String startedAt,
            String finishedAt
    ) {
    }

    @Transactional
    public RegressionDtos.RunDetail ingest(IngestCommand command) {
        UserPrincipal principal = requireAdmin();
        if (command.body() == null || command.body().isBlank()) {
            throw ApiException.validation("Report body is empty");
        }
        if (command.body().length() > MAX_BODY_CHARS) {
            throw ApiException.validation("Report body exceeds 2 MB");
        }
        RegressionReportParser.ParsedReport parsed;
        try {
            parsed = RegressionReportParser.parse(command.contentType(), command.body());
        } catch (IllegalArgumentException ex) {
            throw ApiException.validation(ex.getMessage() == null ? "Invalid report" : ex.getMessage());
        }
        RegressionReportParser.Summary summary = RegressionReportParser.summarize(parsed.cases());
        if (summary.caseCount() != parsed.cases().size()
                || summary.passed() != parsed.summary().passed()
                || summary.failed() != parsed.summary().failed()
                || summary.skipped() != parsed.summary().skipped()) {
            throw new IllegalStateException("Regression summary does not match parsed cases");
        }
        RegressionReportParser.Hints hints = parsed.hints();
        String gitRepo = requireRepo(firstText(command.gitRepo(), hints.gitRepo()));
        String gitBranch = requireBranch(firstText(command.gitBranch(), hints.gitBranch()));
        String gitSha = requireSha(firstText(command.gitSha(), hints.gitSha()));
        String suiteName = requireSuite(firstText(command.suiteName(), hints.suiteName()));
        Instant started = firstInstant(
                parseOptional(command.startedAt(), "startedAt"),
                hints.startedAt(),
                Instant.now());
        Instant finished = firstInstant(
                parseOptional(command.finishedAt(), "finishedAt"),
                hints.finishedAt(),
                started);
        if (finished.isBefore(started)) {
            throw ApiException.validation("finishedAt is before startedAt");
        }
        UUID id = repository.insert(
                gitRepo,
                gitBranch,
                gitSha,
                suiteName,
                started,
                finished,
                principal.userId(),
                parsed.cases());
        return repository.find(id).orElseThrow(() -> ApiException.notFound("Regression run not found"));
    }

    @Transactional(readOnly = true)
    public List<RegressionDtos.RunSummary> list(int limit) {
        requireAdmin();
        int bounded = Math.max(1, Math.min(limit, MAX_LIMIT));
        return repository.list(bounded);
    }

    @Transactional(readOnly = true)
    public RegressionDtos.RunDetail get(UUID id) {
        requireAdmin();
        return repository.find(id).orElseThrow(() -> ApiException.notFound("Regression run not found"));
    }

    private UserPrincipal requireAdmin() {
        UserPrincipal principal = orgAccessService.requirePrincipal();
        if (!organizationRepository.hasRoleAtLeast(principal.userId(), OrgRole.ADMIN)) {
            throw ApiException.forbidden(
                    "Regression runs are platform-scoped and require OWNER or ADMIN on at least one organization");
        }
        return principal;
    }

    private static Instant parseOptional(String raw, String field) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return RegressionReportParser.parseInstant(raw);
        } catch (IllegalArgumentException ex) {
            throw ApiException.validation(field + " is not an ISO-8601 timestamp");
        }
    }

    private static String requireRepo(String raw) {
        String value = trimToNull(raw);
        if (value == null || value.length() > 500 || hasControl(value) || value.indexOf(' ') >= 0) {
            throw ApiException.validation("gitRepo is required (max 500 characters, no spaces)");
        }
        return value;
    }

    private static String requireBranch(String raw) {
        String value = trimToNull(raw);
        if (value == null || value.length() > 255 || hasControl(value)) {
            throw ApiException.validation("gitBranch is required (max 255 characters)");
        }
        return value;
    }

    private static String requireSha(String raw) {
        String value = trimToNull(raw);
        if (value == null || !value.matches("(?i)[0-9a-f]{7,64}")) {
            throw ApiException.validation("gitSha must be 7-64 hexadecimal characters");
        }
        return value.toLowerCase(Locale.ROOT);
    }

    private static String requireSuite(String raw) {
        String value = trimToNull(raw);
        if (value == null || value.length() > 120 || hasControl(value)) {
            throw ApiException.validation("suiteName is required (max 120 characters)");
        }
        return value;
    }

    private static boolean hasControl(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < 0x20 || c == 0x7f) {
                return true;
            }
        }
        return false;
    }

    private static String firstText(String primary, String fallback) {
        String value = trimToNull(primary);
        return value != null ? value : trimToNull(fallback);
    }

    private static Instant firstInstant(Instant primary, Instant fallback, Instant last) {
        if (primary != null) {
            return primary;
        }
        if (fallback != null) {
            return fallback;
        }
        return last;
    }

    private static String trimToNull(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim();
        return value.isEmpty() ? null : value;
    }
}
