package com.agentbench.identity.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * SAML 2.0 Service Provider settings. Disabled by default; configure via env / application props.
 * Org-scoped multi-IdP rows in {@code identity.saml_identity_providers} supplement this;
 * the env/props IdP is the fallback when no enabled DB IdPs exist.
 */
@ConfigurationProperties(prefix = "agentbench.saml")
public class SamlProperties {

    private boolean enabled;
    private String registrationId = "agentbench";
    private String displayName = "Enterprise SSO";
    /** SP entity ID registered at the IdP. */
    private String entityId = "http://localhost:8080/saml2/metadata";
    /** Assertion Consumer Service URL (HTTP-POST). */
    private String acsUrl = "http://localhost:8080/api/v1/auth/saml/acs";
    /** IdP metadata URL (preferred). Mutually sufficient with {@link #metadataXml}. */
    private String metadataUrl = "";
    /** Inline IdP metadata XML (fixture / air-gapped). */
    private String metadataXml = "";
    /** Browser landing page after ACS (tokens in URL fragment). */
    private String successRedirectUrl = "http://localhost:3000/";
    private AttributeMapping attributeMapping = new AttributeMapping();
    /**
     * Optional SP signing credential for HTTP-Redirect AuthnRequests + SP metadata.
     * Default off so local Compose demos stay unsigned.
     */
    private Signing signing = new Signing();

    /** SP is ready to accept IdP configs (env or DB) when enabled with entity + ACS. */
    public boolean isSpReady() {
        return enabled
                && entityId != null && !entityId.isBlank()
                && acsUrl != null && !acsUrl.isBlank();
    }

    /** Env/props single IdP is fully configured (used as fallback when DB has no enabled IdPs). */
    public boolean isEnvIdpConfigured() {
        return isSpReady() && hasEnvMetadata();
    }

    /** @deprecated Prefer {@link #isEnvIdpConfigured()} or {@link #isSpReady()}. */
    public boolean isConfigured() {
        return isEnvIdpConfigured();
    }

    public boolean hasEnvMetadata() {
        return (metadataUrl != null && !metadataUrl.isBlank())
                || (metadataXml != null && !metadataXml.isBlank());
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getRegistrationId() {
        return registrationId;
    }

    public void setRegistrationId(String registrationId) {
        this.registrationId = registrationId;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public String getEntityId() {
        return entityId;
    }

    public void setEntityId(String entityId) {
        this.entityId = entityId;
    }

    public String getAcsUrl() {
        return acsUrl;
    }

    public void setAcsUrl(String acsUrl) {
        this.acsUrl = acsUrl;
    }

    public String getMetadataUrl() {
        return metadataUrl;
    }

    public void setMetadataUrl(String metadataUrl) {
        this.metadataUrl = metadataUrl;
    }

    public String getMetadataXml() {
        return metadataXml;
    }

    public void setMetadataXml(String metadataXml) {
        this.metadataXml = metadataXml;
    }

    public String getSuccessRedirectUrl() {
        return successRedirectUrl;
    }

    public void setSuccessRedirectUrl(String successRedirectUrl) {
        this.successRedirectUrl = successRedirectUrl;
    }

    public AttributeMapping getAttributeMapping() {
        return attributeMapping;
    }

    public void setAttributeMapping(AttributeMapping attributeMapping) {
        this.attributeMapping = attributeMapping;
    }

    public Signing getSigning() {
        return signing;
    }

    public void setSigning(Signing signing) {
        this.signing = signing;
    }

    /**
     * Platform-level SP signing keys (one SP entity → one credential for all IdPs).
     * Provide PEM content via env or PEM file paths. When {@code enabled=false} (default),
     * AuthnRequests stay unsigned even if key material is present.
     */
    public static class Signing {
        private boolean enabled;
        /** Inline PEM private key ({@code BEGIN PRIVATE KEY} / {@code BEGIN RSA PRIVATE KEY}). */
        private String privateKey = "";
        /** Filesystem path to PEM private key (preferred in Compose/K8s mounts). */
        private String privateKeyPath = "";
        /** Inline PEM X.509 certificate. */
        private String certificate = "";
        /** Filesystem path to PEM X.509 certificate. */
        private String certificatePath = "";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getPrivateKey() {
            return privateKey;
        }

        public void setPrivateKey(String privateKey) {
            this.privateKey = privateKey;
        }

        public String getPrivateKeyPath() {
            return privateKeyPath;
        }

        public void setPrivateKeyPath(String privateKeyPath) {
            this.privateKeyPath = privateKeyPath;
        }

        public String getCertificate() {
            return certificate;
        }

        public void setCertificate(String certificate) {
            this.certificate = certificate;
        }

        public String getCertificatePath() {
            return certificatePath;
        }

        public void setCertificatePath(String certificatePath) {
            this.certificatePath = certificatePath;
        }

        public boolean hasKeyMaterialConfigured() {
            return hasText(privateKey) || hasText(privateKeyPath);
        }

        public boolean hasCertificateConfigured() {
            return hasText(certificate) || hasText(certificatePath);
        }

        private static boolean hasText(String value) {
            return value != null && !value.isBlank();
        }
    }

    public static class AttributeMapping {
        /** SAML attribute name (or URI) for email. Falls back to NameID when absent. */
        private String email = "email";
        /** SAML attribute name (or URI) for display name. */
        private String displayName = "displayName";

        public String getEmail() {
            return email;
        }

        public void setEmail(String email) {
            this.email = email;
        }

        public String getDisplayName() {
            return displayName;
        }

        public void setDisplayName(String displayName) {
            this.displayName = displayName;
        }
    }
}
