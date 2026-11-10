package com.agentbench.identity.service;

import com.agentbench.identity.api.dto.AuthDtos;
import com.agentbench.identity.auth.AbstractOidcAuthProvider;
import com.agentbench.identity.auth.AuthCredentials;
import com.agentbench.identity.auth.AuthProvider;
import com.agentbench.identity.auth.OidcProperties;
import com.agentbench.identity.infra.OidcLoginStateRepository;
import com.agentbench.platform.error.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

@Service
public class OidcAuthService {

    private static final long STATE_TTL_SECONDS = 600L;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final OidcProperties oidcProperties;
    private final OidcLoginStateRepository loginStateRepository;
    private final List<AuthProvider> authProviders;
    private final AuthService authService;

    public OidcAuthService(
            OidcProperties oidcProperties,
            OidcLoginStateRepository loginStateRepository,
            List<AuthProvider> authProviders,
            AuthService authService) {
        this.oidcProperties = oidcProperties;
        this.loginStateRepository = loginStateRepository;
        this.authProviders = authProviders;
        this.authService = authService;
    }

    public AbstractOidcAuthProvider requireOidcProvider(String providerId) {
        String id = normalizeProvider(providerId);
        AuthProvider provider = authProviders.stream()
                .filter(p -> p.supports(id))
                .findFirst()
                .orElseThrow(() -> ApiException.validation("Unsupported auth provider: " + id));
        if (!(provider instanceof AbstractOidcAuthProvider oidc) || !oidc.enabled()) {
            throw ApiException.validation("Auth provider not configured: " + id);
        }
        return oidc;
    }

    @Transactional
    public URI beginAuthorization(String providerId) {
        AbstractOidcAuthProvider provider = requireOidcProvider(providerId);
        var config = provider.config();
        String state = randomToken();
        String nonce = randomToken();
        loginStateRepository.insert(
                state,
                provider.id(),
                nonce,
                oidcProperties.getSuccessRedirectUrl(),
                Instant.now().plusSeconds(STATE_TTL_SECONDS)
        );

        UriComponentsBuilder builder = UriComponentsBuilder
                .fromUriString(config.getAuthorizationUri())
                .queryParam("response_type", "code")
                .queryParam("client_id", config.getClientId())
                .queryParam("redirect_uri", config.getRedirectUri())
                .queryParam("scope", config.getScopes())
                .queryParam("state", state);

        if ("google".equals(provider.id())) {
            builder.queryParam("nonce", nonce);
            builder.queryParam("access_type", "online");
            builder.queryParam("include_granted_scopes", "true");
            builder.queryParam("prompt", "select_account");
        }

        return builder.encode().build().toUri();
    }

    @Transactional
    public AuthDtos.TokenResponse completeWithCode(String providerId, String code, String state) {
        return complete(providerId, code, state).tokens();
    }

    @Transactional
    public URI completeBrowserCallback(String providerId, String code, String state) {
        Completion completion = complete(providerId, code, state);
        return buildSuccessRedirect(completion.redirectTo(), completion.tokens());
    }

    private Completion complete(String providerId, String code, String state) {
        if (code == null || code.isBlank()) {
            throw ApiException.validation("authorization code is required");
        }
        if (state == null || state.isBlank()) {
            throw ApiException.validation("state is required");
        }
        AbstractOidcAuthProvider provider = requireOidcProvider(providerId);
        var consumed = loginStateRepository.consume(state.trim(), provider.id())
                .orElseThrow(() -> ApiException.unauthorized("Invalid or expired OIDC state"));
        var principal = provider.authenticate(new AuthCredentials(
                provider.id(),
                null,
                null,
                code.trim()
        ));
        AuthDtos.TokenResponse tokens = authService.issueTokensForPrincipal(principal.userId(), principal.email());
        String redirectTo = consumed.redirectTo() == null || consumed.redirectTo().isBlank()
                ? oidcProperties.getSuccessRedirectUrl()
                : consumed.redirectTo();
        return new Completion(tokens, redirectTo);
    }

    static URI buildSuccessRedirect(String base, AuthDtos.TokenResponse tokens) {
        String fragment = UriComponentsBuilder.newInstance()
                .queryParam("access_token", tokens.accessToken())
                .queryParam("refresh_token", tokens.refreshToken())
                .queryParam("token_type", tokens.tokenType())
                .queryParam("expires_in", tokens.expiresInSeconds())
                .build()
                .getQuery();
        String normalized = base.contains("#") ? base.substring(0, base.indexOf('#')) : base;
        return URI.create(normalized + "#" + fragment);
    }

    private static String normalizeProvider(String providerId) {
        if (providerId == null || providerId.isBlank()) {
            throw ApiException.validation("provider is required");
        }
        return providerId.trim().toLowerCase(Locale.ROOT);
    }

    private static String randomToken() {
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    private record Completion(AuthDtos.TokenResponse tokens, String redirectTo) {
    }
}
