package com.agentbench.identity.service;

import com.agentbench.identity.api.dto.AuthDtos;
import com.agentbench.identity.auth.AuthCredentials;
import com.agentbench.identity.auth.AuthProvider;
import com.agentbench.identity.infra.OrganizationRepository;
import com.agentbench.identity.infra.RefreshTokenRepository;
import com.agentbench.identity.infra.UserRepository;
import com.agentbench.identity.security.JwtService;
import com.agentbench.identity.security.UserPrincipal;
import com.agentbench.platform.error.ApiException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

@Service
public class AuthService {

    private final UserRepository userRepository;
    private final OrganizationRepository organizationRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final List<AuthProvider> authProviders;

    public AuthService(
            UserRepository userRepository,
            OrganizationRepository organizationRepository,
            RefreshTokenRepository refreshTokenRepository,
            PasswordEncoder passwordEncoder,
            JwtService jwtService,
            List<AuthProvider> authProviders) {
        this.userRepository = userRepository;
        this.organizationRepository = organizationRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.authProviders = authProviders;
    }

    @Transactional
    public AuthDtos.TokenResponse register(AuthDtos.RegisterRequest request) {
        String email = normalizeEmail(request.email());
        if (userRepository.findByEmail(email).isPresent()) {
            throw ApiException.conflict("Email already registered");
        }
        try {
            var user = userRepository.insert(
                    email,
                    passwordEncoder.encode(request.password()),
                    request.displayName().trim()
            );
            return issueTokens(user.id(), user.email());
        } catch (DuplicateKeyException ex) {
            throw ApiException.conflict("Email already registered");
        }
    }

    @Transactional
    public AuthDtos.TokenResponse login(AuthDtos.LoginRequest request) {
        String providerType = request.provider() == null || request.provider().isBlank()
                ? "local"
                : request.provider().trim().toLowerCase(Locale.ROOT);
        AuthProvider provider = authProviders.stream()
                .filter(p -> p.supports(providerType))
                .findFirst()
                .orElseThrow(() -> ApiException.validation("Unsupported auth provider: " + providerType));
        var principal = provider.authenticate(new AuthCredentials(
                providerType,
                normalizeEmail(request.email()),
                request.password()
        ));
        return issueTokens(principal.userId(), principal.email());
    }

    @Transactional
    public AuthDtos.TokenResponse refresh(AuthDtos.RefreshRequest request) {
        String hash = hashToken(request.refreshToken());
        var row = refreshTokenRepository.findActiveByHash(hash)
                .orElseThrow(() -> ApiException.unauthorized("Invalid refresh token"));
        refreshTokenRepository.revokeByHash(hash);
        var user = userRepository.findById(row.userId())
                .orElseThrow(() -> ApiException.unauthorized("Invalid refresh token"));
        return issueTokens(user.id(), user.email());
    }

    @Transactional
    public void logout(UserPrincipal principal, AuthDtos.LogoutRequest request) {
        if (request != null && request.refreshToken() != null && !request.refreshToken().isBlank()) {
            refreshTokenRepository.revokeByHash(hashToken(request.refreshToken()));
        } else if (principal != null) {
            refreshTokenRepository.revokeAllForUser(principal.userId());
        }
    }

    @Transactional(readOnly = true)
    public AuthDtos.MeResponse me(UserPrincipal principal) {
        var user = userRepository.findById(principal.userId())
                .orElseThrow(() -> ApiException.unauthorized("User not found"));
        List<AuthDtos.MembershipView> memberships = organizationRepository.findForUser(user.id()).stream()
                .map(org -> {
                    var membership = organizationRepository.findMembership(org.id(), user.id()).orElseThrow();
                    return new AuthDtos.MembershipView(org.id(), org.name(), org.slug(), membership.role().name());
                })
                .toList();
        return new AuthDtos.MeResponse(user.id(), user.email(), user.displayName(), user.createdAt(), memberships);
    }

    private AuthDtos.TokenResponse issueTokens(java.util.UUID userId, String email) {
        String access = jwtService.createAccessToken(userId, email);
        String refresh = jwtService.createRefreshTokenValue();
        Instant expiresAt = Instant.now().plusSeconds(jwtService.refreshTtlSeconds());
        refreshTokenRepository.insert(userId, hashToken(refresh), expiresAt);
        return new AuthDtos.TokenResponse(access, refresh, "Bearer", jwtService.accessTtlSeconds());
    }

    static String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    static String hashToken(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashed);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }
}
