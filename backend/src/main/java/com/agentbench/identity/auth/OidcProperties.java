package com.agentbench.identity.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * OIDC / OAuth2 authorization-code settings. Secrets must come from env; never log them.
 */
@ConfigurationProperties(prefix = "agentbench.oidc")
public class OidcProperties {

    /**
     * Browser landing page after successful OIDC callback (tokens appended as URL fragment).
     */
    private String successRedirectUrl = "http://localhost:3000/";

    private ProviderProperties google = new ProviderProperties();
    private ProviderProperties github = new ProviderProperties();

    public String getSuccessRedirectUrl() {
        return successRedirectUrl;
    }

    public void setSuccessRedirectUrl(String successRedirectUrl) {
        this.successRedirectUrl = successRedirectUrl;
    }

    public ProviderProperties getGoogle() {
        return google;
    }

    public void setGoogle(ProviderProperties google) {
        this.google = google;
    }

    public ProviderProperties getGithub() {
        return github;
    }

    public void setGithub(ProviderProperties github) {
        this.github = github;
    }

    public ProviderProperties provider(String id) {
        return switch (id.toLowerCase()) {
            case "google" -> google;
            case "github" -> github;
            default -> null;
        };
    }

    public static class ProviderProperties {
        private boolean enabled;
        private String clientId = "";
        private String clientSecret = "";
        private String redirectUri = "";
        private String authorizationUri = "";
        private String tokenUri = "";
        private String userInfoUri = "";
        /** GitHub-only secondary endpoint for primary email when /user omits it. */
        private String emailUri = "";
        private String scopes = "";

        public boolean isConfigured() {
            return enabled
                    && clientId != null && !clientId.isBlank()
                    && clientSecret != null && !clientSecret.isBlank()
                    && redirectUri != null && !redirectUri.isBlank()
                    && authorizationUri != null && !authorizationUri.isBlank()
                    && tokenUri != null && !tokenUri.isBlank()
                    && userInfoUri != null && !userInfoUri.isBlank();
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getClientId() {
            return clientId;
        }

        public void setClientId(String clientId) {
            this.clientId = clientId;
        }

        public String getClientSecret() {
            return clientSecret;
        }

        public void setClientSecret(String clientSecret) {
            this.clientSecret = clientSecret;
        }

        public String getRedirectUri() {
            return redirectUri;
        }

        public void setRedirectUri(String redirectUri) {
            this.redirectUri = redirectUri;
        }

        public String getAuthorizationUri() {
            return authorizationUri;
        }

        public void setAuthorizationUri(String authorizationUri) {
            this.authorizationUri = authorizationUri;
        }

        public String getTokenUri() {
            return tokenUri;
        }

        public void setTokenUri(String tokenUri) {
            this.tokenUri = tokenUri;
        }

        public String getUserInfoUri() {
            return userInfoUri;
        }

        public void setUserInfoUri(String userInfoUri) {
            this.userInfoUri = userInfoUri;
        }

        public String getEmailUri() {
            return emailUri;
        }

        public void setEmailUri(String emailUri) {
            this.emailUri = emailUri;
        }

        public String getScopes() {
            return scopes;
        }

        public void setScopes(String scopes) {
            this.scopes = scopes;
        }
    }
}
