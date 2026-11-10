package com.agentbench.identity.auth;

import com.agentbench.identity.infra.UserIdentityRepository;
import com.agentbench.identity.infra.UserRepository;
import org.springframework.stereotype.Component;

@Component
public class GoogleOidcAuthProvider extends AbstractOidcAuthProvider {

    public static final String TYPE = "google";

    public GoogleOidcAuthProvider(
            OidcProperties oidcProperties,
            OidcHttpClient oidcHttpClient,
            UserRepository userRepository,
            UserIdentityRepository userIdentityRepository) {
        super(oidcProperties.getGoogle(), oidcHttpClient, userRepository, userIdentityRepository);
    }

    @Override
    public String id() {
        return TYPE;
    }
}
