package com.agentbench.analytics.service;

import com.agentbench.analytics.api.dto.AnalyticsDtos;
import com.agentbench.analytics.config.PublicLeaderboardProperties;
import com.agentbench.analytics.domain.LeaderboardComment;
import com.agentbench.analytics.domain.LeaderboardPublication;
import com.agentbench.analytics.domain.LeaderboardVote;
import com.agentbench.analytics.infra.LeaderboardEngagementRepository;
import com.agentbench.analytics.infra.PublicLeaderboardRepository;
import com.agentbench.identity.domain.Membership;
import com.agentbench.identity.domain.OrgRole;
import com.agentbench.identity.security.OrgAccessService;
import com.agentbench.identity.security.UserPrincipal;
import com.agentbench.platform.error.ApiException;
import com.agentbench.platform.rls.RlsContext;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class PublicEngagementService {

    private static final Pattern FINGERPRINT = Pattern.compile("^[a-zA-Z0-9_-]{8,64}$");
    private static final Pattern DISPLAY_NAME = Pattern.compile("^[\\p{L}\\p{N} ._'\\-]{1,80}$");

    private final PublicLeaderboardRepository publicLeaderboardRepository;
    private final LeaderboardEngagementRepository engagementRepository;
    private final OrgAccessService orgAccessService;
    private final PublicEngagementRateLimiter rateLimiter;
    private final PublicLeaderboardProperties properties;

    public PublicEngagementService(
            PublicLeaderboardRepository publicLeaderboardRepository,
            LeaderboardEngagementRepository engagementRepository,
            OrgAccessService orgAccessService,
            PublicEngagementRateLimiter rateLimiter,
            PublicLeaderboardProperties properties) {
        this.publicLeaderboardRepository = publicLeaderboardRepository;
        this.engagementRepository = engagementRepository;
        this.orgAccessService = orgAccessService;
        this.rateLimiter = rateLimiter;
        this.properties = properties;
    }

    public AnalyticsDtos.PublicCommentListResponse listComments(String slug, Integer page, Integer size) {
        LeaderboardPublication pub = requireActivePublication(slug);
        int pageNum = normalizePage(page);
        int pageSize = normalizeSize(size, 50);
        return RlsContext.callWithOrg(pub.orgId(), () -> {
            long total = engagementRepository.countActiveComments(pub.orgId(), pub.id());
            List<AnalyticsDtos.PublicCommentResponse> comments = engagementRepository
                    .listActiveComments(pub.orgId(), pub.id(), pageSize, pageNum * pageSize)
                    .stream()
                    .map(this::toPublicComment)
                    .toList();
            return new AnalyticsDtos.PublicCommentListResponse(
                    pub.publicSlug(),
                    comments,
                    new AnalyticsDtos.PageMeta(pageNum, pageSize, total));
        });
    }

    @Transactional
    public AnalyticsDtos.PublicCommentResponse createComment(
            String slug,
            AnalyticsDtos.CreatePublicCommentRequest request,
            String clientKey) {
        rateLimiter.check(PublicEngagementRateLimiter.Action.COMMENT, clientKey);
        LeaderboardPublication pub = requireActivePublication(slug);
        if (request == null || request.body() == null || request.body().isBlank()) {
            throw ApiException.validation("Comment body is required");
        }
        String body = request.body().trim();
        if (body.length() > properties.getMaxCommentBodyLength()) {
            throw ApiException.validation(
                    "Comment body must be at most " + properties.getMaxCommentBodyLength() + " characters");
        }
        Optional<UserPrincipal> principal = optionalPrincipal();
        String displayName = resolveDisplayName(request.displayName(), principal.orElse(null));
        UUID authorUserId = principal.map(UserPrincipal::userId).orElse(null);
        LeaderboardComment comment = RlsContext.callWithOrg(pub.orgId(), () ->
                engagementRepository.insertComment(
                        pub.orgId(), pub.id(), body, displayName, authorUserId));
        return toPublicComment(comment);
    }

    @Transactional
    public void moderateDeleteComment(UUID orgId, UUID versionId, UUID commentId) {
        Membership actor = orgAccessService.requireMembership(orgId, OrgRole.ADMIN);
        LeaderboardPublication pub = publicLeaderboardRepository.findByOrgAndVersion(orgId, versionId)
                .orElseThrow(() -> ApiException.notFound("Leaderboard publication not found"));
        RlsContext.runWithOrg(orgId, () -> {
            LeaderboardComment comment = engagementRepository.findCommentById(orgId, commentId)
                    .orElseThrow(() -> ApiException.notFound("Comment not found"));
            if (!comment.publicationId().equals(pub.id())) {
                throw ApiException.notFound("Comment not found on this leaderboard");
            }
            if (comment.isDeleted()) {
                return;
            }
            engagementRepository.softDeleteComment(orgId, commentId, actor.userId());
        });
    }

    public AnalyticsDtos.PublicVoteSummaryResponse voteSummary(String slug, String voterFingerprint) {
        LeaderboardPublication pub = requireActivePublication(slug);
        String voterKey = resolveVoterKey(voterFingerprint, optionalPrincipal().orElse(null), false);
        return RlsContext.callWithOrg(pub.orgId(), () -> buildVoteSummary(pub, voterKey));
    }

    @Transactional
    public AnalyticsDtos.PublicVoteSummaryResponse castVote(
            String slug,
            AnalyticsDtos.CastPublicVoteRequest request,
            String clientKey) {
        rateLimiter.check(PublicEngagementRateLimiter.Action.VOTE, clientKey);
        LeaderboardPublication pub = requireActivePublication(slug);
        if (request == null) {
            throw ApiException.validation("Vote request is required");
        }
        int value = request.value();
        if (value != 1 && value != -1) {
            throw ApiException.validation("Vote value must be 1 (up) or -1 (down)");
        }
        Optional<UserPrincipal> principal = optionalPrincipal();
        String voterKey = resolveVoterKey(request.voterFingerprint(), principal.orElse(null), true);
        UUID authorUserId = principal.map(UserPrincipal::userId).orElse(null);
        return RlsContext.callWithOrg(pub.orgId(), () -> {
            engagementRepository.upsertVote(pub.orgId(), pub.id(), voterKey, value, authorUserId);
            return buildVoteSummary(pub, voterKey);
        });
    }

    private AnalyticsDtos.PublicVoteSummaryResponse buildVoteSummary(
            LeaderboardPublication pub,
            String voterKey) {
        LeaderboardEngagementRepository.VoteTallies tallies =
                engagementRepository.tallies(pub.orgId(), pub.id());
        Integer myVote = null;
        if (voterKey != null) {
            myVote = engagementRepository.findVote(pub.orgId(), pub.id(), voterKey)
                    .map(LeaderboardVote::value)
                    .orElse(null);
        }
        return new AnalyticsDtos.PublicVoteSummaryResponse(
                pub.publicSlug(),
                tallies.upCount(),
                tallies.downCount(),
                tallies.score(),
                myVote);
    }

    private LeaderboardPublication requireActivePublication(String slug) {
        String normalized = normalizeSlug(slug);
        return publicLeaderboardRepository.findActiveBySlug(normalized)
                .orElseThrow(() -> ApiException.notFound("Public leaderboard not found"));
    }

    private AnalyticsDtos.PublicCommentResponse toPublicComment(LeaderboardComment comment) {
        return new AnalyticsDtos.PublicCommentResponse(
                comment.id(),
                comment.body(),
                comment.displayName(),
                comment.authorUserId() != null,
                comment.createdAt());
    }

    private String resolveDisplayName(String requested, UserPrincipal principal) {
        if (requested != null && !requested.isBlank()) {
            String trimmed = requested.trim();
            if (trimmed.length() > properties.getMaxDisplayNameLength()) {
                throw ApiException.validation(
                        "Display name must be at most " + properties.getMaxDisplayNameLength() + " characters");
            }
            if (!DISPLAY_NAME.matcher(trimmed).matches()) {
                throw ApiException.validation(
                        "Display name may only contain letters, numbers, spaces, and . _ ' -");
            }
            return trimmed;
        }
        if (principal != null && principal.email() != null && !principal.email().isBlank()) {
            String local = principal.email().split("@", 2)[0];
            if (!local.isBlank()) {
                return local.length() > properties.getMaxDisplayNameLength()
                        ? local.substring(0, properties.getMaxDisplayNameLength())
                        : local;
            }
        }
        return "Anonymous";
    }

    private String resolveVoterKey(String fingerprint, UserPrincipal principal, boolean requiredForAnon) {
        if (principal != null) {
            return "user:" + principal.userId();
        }
        if (fingerprint == null || fingerprint.isBlank()) {
            if (requiredForAnon) {
                throw ApiException.validation("voterFingerprint is required for anonymous votes");
            }
            return null;
        }
        String trimmed = fingerprint.trim();
        if (!FINGERPRINT.matcher(trimmed).matches()) {
            throw ApiException.validation(
                    "voterFingerprint must be 8–64 chars of letters, digits, _ or -");
        }
        return "anon:" + trimmed;
    }

    private static Optional<UserPrincipal> optionalPrincipal() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof UserPrincipal principal) {
            return Optional.of(principal);
        }
        return Optional.empty();
    }

    private static String normalizeSlug(String slug) {
        if (slug == null || slug.isBlank()) {
            throw ApiException.validation("publicSlug is required");
        }
        return slug.trim().toLowerCase(Locale.ROOT);
    }

    private static int normalizePage(Integer page) {
        if (page == null || page < 0) {
            return 0;
        }
        return page;
    }

    private static int normalizeSize(Integer size, int defaultSize) {
        if (size == null || size < 1) {
            return defaultSize;
        }
        return Math.min(size, 100);
    }
}
