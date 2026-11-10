package com.agentbench.identity.auth;

import com.agentbench.identity.infra.UserIdentityRepository;
import com.agentbench.identity.infra.UserRepository;
import com.agentbench.platform.error.ApiException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * SAML 2.0 AuthProvider SPI implementation. Links or creates a local user after assertion validation.
 */
@Component
public class SamlAuthProvider implements AuthProvider {

    public static final String TYPE = "saml";

    private final SamlProperties properties;
    private final UserRepository userRepository;
    private final UserIdentityRepository userIdentityRepository;

    public SamlAuthProvider(
            SamlProperties properties,
            UserRepository userRepository,
            UserIdentityRepository userIdentityRepository) {
        this.properties = properties;
        this.userRepository = userRepository;
        this.userIdentityRepository = userIdentityRepository;
    }

    @Override
    public String id() {
        return TYPE;
    }

    @Override
    public boolean supports(String providerType) {
        return TYPE.equalsIgnoreCase(providerType);
    }

    @Override
    public boolean enabled() {
        return properties.isConfigured();
    }

    public SamlProperties properties() {
        return properties;
    }

    @Override
    public AuthenticatedPrincipal authenticate(AuthCredentials credentials) {
        throw ApiException.validation("Use SAML ACS / token endpoints for provider " + TYPE);
    }

    public AuthenticatedPrincipal linkOrCreate(SamlUserProfile profile) {
        if (!enabled()) {
            throw ApiException.validation("Auth provider not configured: " + TYPE);
        }
        String email = profile.email().trim().toLowerCase(Locale.ROOT);
        String displayName = profile.displayName() == null || profile.displayName().isBlank()
                ? email
                : profile.displayName().trim();

        var existingIdentity = userIdentityRepository.findByProviderAndSubject(TYPE, profile.subject());
        if (existingIdentity.isPresent()) {
            var user = userRepository.findById(existingIdentity.get().userId())
                    .orElseThrow(() -> ApiException.unauthorized("Linked user missing"));
            return new AuthenticatedPrincipal(user.id(), user.email(), user.displayName());
        }

        var byEmail = userRepository.findByEmail(email);
        try {
            if (byEmail.isPresent()) {
                var user = byEmail.get();
                userIdentityRepository.insert(user.id(), TYPE, profile.subject(), email);
                return new AuthenticatedPrincipal(user.id(), user.email(), user.displayName());
            }
            var created = userRepository.insert(email, null, displayName);
            userIdentityRepository.insert(created.id(), TYPE, profile.subject(), email);
            return new AuthenticatedPrincipal(created.id(), created.email(), created.displayName());
        } catch (DuplicateKeyException ex) {
            return userIdentityRepository.findByProviderAndSubject(TYPE, profile.subject())
                    .flatMap(identity -> userRepository.findById(identity.userId()))
                    .or(() -> userRepository.findByEmail(email))
                    .map(user -> new AuthenticatedPrincipal(user.id(), user.email(), user.displayName()))
                    .orElseThrow(() -> ApiException.conflict("Could not link SAML identity"));
        }
    }
}
