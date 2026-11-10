package com.agentbench.identity.auth;

import com.agentbench.platform.error.ApiException;
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.bouncycastle.openssl.PEMKeyPair;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.saml2.core.Saml2X509Credential;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.PrivateKey;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Base64;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Loads optional platform SP signing credentials from {@link SamlProperties.Signing}.
 * Default off — local Compose demos leave AuthnRequests unsigned.
 */
@Component
public class SamlSpSigningCredentials {

    private static final Logger log = LoggerFactory.getLogger(SamlSpSigningCredentials.class);

    private final SamlProperties properties;
    private final AtomicReference<Optional<Saml2X509Credential>> cached = new AtomicReference<>();

    public SamlSpSigningCredentials(SamlProperties properties) {
        this.properties = properties;
    }

    /** True when signing is enabled and a credential can be loaded. */
    public boolean isActive() {
        return properties.getSigning().isEnabled() && resolve().isPresent();
    }

    public Optional<Saml2X509Credential> optionalCredential() {
        if (!properties.getSigning().isEnabled()) {
            return Optional.empty();
        }
        return resolve();
    }

    public Saml2X509Credential requireCredential() {
        if (!properties.getSigning().isEnabled()) {
            throw ApiException.validation("SAML SP signing is not enabled");
        }
        return resolve().orElseThrow(() -> new ApiException(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "SAML_SP_SIGNING_MISCONFIGURED",
                "SAML SP signing is enabled but private key / certificate could not be loaded"
        ));
    }

    /** Base64 (DER) certificate for SP metadata KeyDescriptor, when signing is active. */
    public Optional<String> certificateDerBase64() {
        return optionalCredential().map(cred -> {
            try {
                return Base64.getEncoder().encodeToString(cred.getCertificate().getEncoded());
            } catch (Exception ex) {
                throw new ApiException(
                        HttpStatus.INTERNAL_SERVER_ERROR,
                        "SAML_SP_SIGNING_MISCONFIGURED",
                        "Unable to encode SP signing certificate"
                );
            }
        });
    }

    /** Clears cached credential (tests). */
    public void evict() {
        cached.set(null);
    }

    private Optional<Saml2X509Credential> resolve() {
        Optional<Saml2X509Credential> existing = cached.get();
        if (existing != null) {
            return existing;
        }
        synchronized (this) {
            existing = cached.get();
            if (existing != null) {
                return existing;
            }
            Optional<Saml2X509Credential> loaded = load();
            cached.set(loaded);
            return loaded;
        }
    }

    private Optional<Saml2X509Credential> load() {
        SamlProperties.Signing signing = properties.getSigning();
        if (!signing.isEnabled()) {
            return Optional.empty();
        }
        if (!signing.hasKeyMaterialConfigured() || !signing.hasCertificateConfigured()) {
            log.error("SAML SP signing enabled but private key and/or certificate are missing");
            return Optional.empty();
        }
        try {
            PrivateKey privateKey = readPrivateKey(signing);
            X509Certificate certificate = readCertificate(signing);
            Saml2X509Credential credential = Saml2X509Credential.signing(privateKey, certificate);
            log.info("Loaded SAML SP signing credential (subject={})", certificate.getSubjectX500Principal());
            return Optional.of(credential);
        } catch (Exception ex) {
            log.error("Failed to load SAML SP signing credential: {}", ex.getMessage());
            return Optional.empty();
        }
    }

    private static PrivateKey readPrivateKey(SamlProperties.Signing signing) throws Exception {
        String pem = resolvePem(signing.getPrivateKey(), signing.getPrivateKeyPath(), "private key");
        try (PEMParser parser = new PEMParser(new StringReader(normalizePem(pem)))) {
            Object object = parser.readObject();
            JcaPEMKeyConverter converter = new JcaPEMKeyConverter();
            if (object instanceof PEMKeyPair keyPair) {
                return converter.getKeyPair(keyPair).getPrivate();
            }
            if (object instanceof PrivateKeyInfo info) {
                return converter.getPrivateKey(info);
            }
            throw new IllegalArgumentException(
                    "Unsupported private key PEM (expected PKCS#8 or PKCS#1 RSA key pair)");
        }
    }

    private static X509Certificate readCertificate(SamlProperties.Signing signing) throws Exception {
        String pem = resolvePem(signing.getCertificate(), signing.getCertificatePath(), "certificate");
        CertificateFactory factory = CertificateFactory.getInstance("X.509");
        try (var in = new java.io.ByteArrayInputStream(normalizePem(pem).getBytes(StandardCharsets.UTF_8))) {
            return (X509Certificate) factory.generateCertificate(in);
        }
    }

    private static String resolvePem(String inline, String path, String label) throws IOException {
        if (inline != null && !inline.isBlank()) {
            String trimmed = inline.trim();
            if (looksLikePem(trimmed)) {
                return trimmed;
            }
            // Inline value that is actually a path (common Compose mistake).
            Path asPath = Path.of(trimmed);
            if (Files.isRegularFile(asPath)) {
                return Files.readString(asPath, StandardCharsets.UTF_8);
            }
            throw new IllegalArgumentException("SAML SP signing " + label + " does not look like PEM");
        }
        if (path != null && !path.isBlank()) {
            Path file = Path.of(path.trim());
            if (!Files.isRegularFile(file)) {
                throw new IllegalArgumentException("SAML SP signing " + label + " path not found: " + path);
            }
            return Files.readString(file, StandardCharsets.UTF_8);
        }
        throw new IllegalArgumentException("SAML SP signing " + label + " is not configured");
    }

    private static boolean looksLikePem(String value) {
        return value.contains("BEGIN ") && value.contains("END ");
    }

    /** Accept escaped newlines from .env single-line PEMs. */
    private static String normalizePem(String pem) {
        return pem.replace("\\n", "\n").trim() + "\n";
    }
}
