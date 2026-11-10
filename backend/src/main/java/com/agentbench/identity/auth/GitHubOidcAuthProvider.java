package com.agentbench.identity.auth;

import com.agentbench.identity.infra.UserIdentityRepository;
import com.agentbench.identity.infra.UserRepository;
import org.springframework.stereotype.Component;

@Component
public class GitHubOidcAuthProvider extends AbstractOidcAuthProvider {

    public static final String TYPE = "github";

    public GitHubOidcAuthProvider(
            OidcProperties oidcProperties,
            OidcHttpClient oidcHttpClient,
            UserRepository userRepository,
            UserIdentityRepository userIdentityRepository) {
        super(oidcProperties.getGithub(), oidcHttpClient, userRepository, userIdentityRepository);
    }

    @Override
    public String id() {
        return TYPE;
    }
}
