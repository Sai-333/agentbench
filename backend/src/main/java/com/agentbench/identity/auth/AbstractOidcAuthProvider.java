package com.agentbench.identity.auth;

import com.agentbench.identity.infra.UserIdentityRepository;
import com.agentbench.identity.infra.UserRepository;
import com.agentbench.platform.error.ApiException;
import org.springframework.dao.DuplicateKeyException;

import java.util.Locale;

/**
 * Shared authorization-code authentication for OIDC/OAuth providers.
 * Links or creates a local user, then returns a principal for JWT issuance.
 */
public abstract class AbstractOidcAuthProvider implements AuthProvider {

    private final OidcProperties.ProviderProperties config;
    private final OidcHttpClient oidcHttpClient;
    private final UserRepository userRepository;
    private final UserIdentityRepository userIdentityRepository;

    protected AbstractOidcAuthProvider(
            OidcProperties.ProviderProperties config,
            OidcHttpClient oidcHttpClient,
            UserRepository userRepository,
            UserIdentityRepository userIdentityRepository) {
        this.config = config;
        this.oidcHttpClient = oidcHttpClient;
        this.userRepository = userRepository;
        this.userIdentityRepository = userIdentityRepository;
    }

    @Override
    public boolean supports(String providerType) {
        return id().equalsIgnoreCase(providerType);
    }

    @Override
    public boolean enabled() {
        return config != null && config.isConfigured();
    }

    public OidcProperties.ProviderProperties config() {
        return config;
    }

    @Override
    public AuthenticatedPrincipal authenticate(AuthCredentials credentials) {
        if (!enabled()) {
            throw ApiException.validation("Auth provider not configured: " + id());
        }
        String code = credentials.authorizationCode();
        if (code == null || code.isBlank()) {
            throw ApiException.validation("authorizationCode required for provider " + id());
        }
        OidcUserProfile profile = oidcHttpClient.exchangeAuthorizationCode(id(), config, code.trim());
        return linkOrCreate(profile);
    }

    private AuthenticatedPrincipal linkOrCreate(OidcUserProfile profile) {
        String email = profile.email().trim().toLowerCase(Locale.ROOT);
        String displayName = profile.displayName() == null || profile.displayName().isBlank()
                ? email
                : profile.displayName().trim();

        var existingIdentity = userIdentityRepository.findByProviderAndSubject(id(), profile.subject());
        if (existingIdentity.isPresent()) {
            var user = userRepository.findById(existingIdentity.get().userId())
                    .orElseThrow(() -> ApiException.unauthorized("Linked user missing"));
            return new AuthenticatedPrincipal(user.id(), user.email(), user.displayName());
        }

        var byEmail = userRepository.findByEmail(email);
        try {
            if (byEmail.isPresent()) {
                var user = byEmail.get();
                userIdentityRepository.insert(user.id(), id(), profile.subject(), email);
                return new AuthenticatedPrincipal(user.id(), user.email(), user.displayName());
            }
            var created = userRepository.insert(email, null, displayName);
            userIdentityRepository.insert(created.id(), id(), profile.subject(), email);
            return new AuthenticatedPrincipal(created.id(), created.email(), created.displayName());
        } catch (DuplicateKeyException ex) {
            // Concurrent first login: re-resolve by identity or email.
            return userIdentityRepository.findByProviderAndSubject(id(), profile.subject())
                    .flatMap(identity -> userRepository.findById(identity.userId()))
                    .or(() -> userRepository.findByEmail(email))
                    .map(user -> new AuthenticatedPrincipal(user.id(), user.email(), user.displayName()))
                    .orElseThrow(() -> ApiException.conflict("Could not link OIDC identity"));
        }
    }
}
