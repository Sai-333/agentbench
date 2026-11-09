package com.agentbench.identity.auth;

import com.agentbench.identity.infra.UserRepository;
import com.agentbench.platform.error.ApiException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
public class LocalPasswordAuthProvider implements AuthProvider {

    public static final String TYPE = "local";

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public LocalPasswordAuthProvider(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public String id() {
        return TYPE;
    }

    @Override
    public boolean supports(String providerType) {
        return TYPE.equalsIgnoreCase(providerType) || providerType == null || providerType.isBlank();
    }

    @Override
    public AuthenticatedPrincipal authenticate(AuthCredentials credentials) {
        var user = userRepository.findByEmail(credentials.email())
                .orElseThrow(() -> ApiException.unauthorized("Invalid credentials"));
        if (user.passwordHash() == null || !passwordEncoder.matches(credentials.password(), user.passwordHash())) {
            throw ApiException.unauthorized("Invalid credentials");
        }
        return new AuthenticatedPrincipal(user.id(), user.email(), user.displayName());
    }
}
