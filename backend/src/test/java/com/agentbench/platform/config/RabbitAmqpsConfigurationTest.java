package com.agentbench.platform.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.amqp.RabbitProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.ssl.NoSuchSslBundleException;
import org.springframework.boot.ssl.SslBundle;
import org.springframework.boot.ssl.SslBundleKey;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.boot.ssl.SslManagerBundle;
import org.springframework.boot.ssl.SslOptions;
import org.springframework.boot.ssl.SslStoreBundle;
import org.springframework.boot.ssl.pem.PemSslStoreBundle;
import org.springframework.boot.ssl.pem.PemSslStoreDetails;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Opt-in AMQPS fail-fast + PEM trust material load without a live broker.
 */
class RabbitAmqpsConfigurationTest {

    @TempDir
    Path tempDir;

    @Test
    void disabledSslSkipsValidation() {
        RabbitProperties props = new RabbitProperties();
        props.getSsl().setEnabled(false);
        assertDoesNotThrow(() -> RabbitAmqpsConfiguration.validateAmqpsConfig(props, null));
        assertFalse(RabbitAmqpsConfiguration.isSslConfigured(props));
    }

    /**
     * Boot 3.4 {@code RabbitProperties.Ssl.determineEnabled()} is
     * {@code enabled || bundle != null}. An empty-string bundle (from
     * {@code bundle: ${ENV:}} when unset) therefore turns SSL on and the
     * connection factory looks up bundle {@code ''}.
     */
    @Test
    void emptyBundleStringForcesSslOnEvenWhenEnabledFalse() {
        RabbitProperties props = new RabbitProperties();
        props.getSsl().setEnabled(false);
        props.getSsl().setBundle("");
        assertTrue(props.getSsl().determineEnabled());
        assertTrue(RabbitAmqpsConfiguration.isSslConfigured(props));
    }

    @Test
    void omittedBundleKeepsSslOffWhenDisabled() {
        RabbitProperties props = new RabbitProperties();
        props.getSsl().setEnabled(false);
        assertNull(props.getSsl().getBundle());
        assertFalse(props.getSsl().determineEnabled());
        assertFalse(RabbitAmqpsConfiguration.isSslConfigured(props));
    }

    @Test
    void applicationYmlOmitsEmptySslBundleDefault() throws Exception {
        Path yml = Path.of("src/main/resources/application.yml");
        if (!Files.isRegularFile(yml)) {
            yml = Path.of("backend/src/main/resources/application.yml");
        }
        assertTrue(Files.isRegularFile(yml), "application.yml not found");
        String text = Files.readString(yml);
        assertFalse(
                Pattern.compile("(?m)^\\s*bundle:\\s*\\$\\{SPRING_RABBITMQ_SSL_BUNDLE:}\\s*$")
                        .matcher(text)
                        .find(),
                "application.yml must not default spring.rabbitmq.ssl.bundle to empty "
                        + "(Boot 3.4 treats non-null empty as SSL-on)");
        assertTrue(
                text.contains("SPRING_RABBITMQ_SSL_BUNDLE"),
                "docs comment should still mention SPRING_RABBITMQ_SSL_BUNDLE for opt-in");
    }

    /**
     * Opt-in AMQPS still works when {@code spring.rabbitmq.ssl.bundle} is set via
     * property/env sources without a YAML {@code bundle:} key (Compose/Helm path).
     */
    @Test
    void propertySourceBindsSslBundleWithoutYamlKey() {
        StandardEnvironment env = new StandardEnvironment();
        env.getPropertySources().addFirst(new MapPropertySource("amqps-overlay", Map.of(
                "spring.rabbitmq.ssl.enabled", "true",
                "spring.rabbitmq.ssl.bundle", "rabbitmq",
                "spring.rabbitmq.port", "5671")));
        RabbitProperties props = Binder.get(env)
                .bind("spring.rabbitmq", RabbitProperties.class)
                .orElseThrow(() -> new IllegalStateException("spring.rabbitmq bind failed"));
        assertEquals(Boolean.TRUE, props.getSsl().getEnabled());
        assertEquals("rabbitmq", props.getSsl().getBundle());
        assertEquals(5671, props.getPort());
        assertTrue(props.getSsl().determineEnabled());
        assertTrue(RabbitAmqpsConfiguration.isSslConfigured(props));
    }

    @Test
    void enabledWithoutBundleOrTrustStoreFails() {
        RabbitProperties props = new RabbitProperties();
        props.getSsl().setEnabled(true);
        IllegalStateException ex = assertThrows(
                IllegalStateException.class,
                () -> RabbitAmqpsConfiguration.validateAmqpsConfig(props, null));
        assertTrue(ex.getMessage().contains("trust-store") || ex.getMessage().contains("bundle"));
    }

    @Test
    void enabledWithClassicTrustStorePasses() {
        RabbitProperties props = new RabbitProperties();
        props.getSsl().setEnabled(true);
        props.getSsl().setTrustStore("file:/tmp/trust.p12");
        assertDoesNotThrow(() -> RabbitAmqpsConfiguration.validateAmqpsConfig(props, null));
        assertTrue(RabbitAmqpsConfiguration.hasClassicTrustStore(props.getSsl()));
        assertTrue(RabbitAmqpsConfiguration.isSslConfigured(props));
    }

    @Test
    void enabledWithMissingBundleFails() {
        RabbitProperties props = new RabbitProperties();
        props.getSsl().setEnabled(true);
        props.getSsl().setBundle("rabbitmq");
        SslBundles empty = new FixedSslBundles(null);
        assertThrows(
                IllegalStateException.class,
                () -> RabbitAmqpsConfiguration.validateAmqpsConfig(props, empty));
    }

    @Test
    void enabledWithPemBundleLoadsTrustMaterial() throws Exception {
        Path out = tempDir.resolve("certs");
        Files.createDirectories(out);
        generateLabCerts(out);

        String ca = out.resolve("ca.crt").toUri().toString();
        String clientCrt = out.resolve("client.crt").toUri().toString();
        String clientKey = out.resolve("client.key").toUri().toString();
        PemSslStoreDetails trust = PemSslStoreDetails.forCertificate(ca);
        PemSslStoreDetails key = new PemSslStoreDetails(null, clientCrt, clientKey);
        SslStoreBundle stores = new PemSslStoreBundle(key, trust);
        SslBundle bundle = SslBundle.of(stores);
        SslBundles bundles = new FixedSslBundles(bundle);

        RabbitProperties props = new RabbitProperties();
        props.getSsl().setEnabled(true);
        props.getSsl().setBundle("rabbitmq");
        props.setPort(5671);

        assertDoesNotThrow(() -> RabbitAmqpsConfiguration.validateAmqpsConfig(props, bundles));
        assertTrue(RabbitAmqpsConfiguration.isSslConfigured(props));

        KeyStore trustStore = bundles.getBundle("rabbitmq").getStores().getTrustStore();
        assertTrue(trustStore.size() >= 1);
        // Managers must build without error (same path Spring AMQP uses via SSL bundle).
        SslManagerBundle managers = bundles.getBundle("rabbitmq").getManagers();
        assertTrue(managers.getTrustManagers().length >= 1);
        assertTrue(SslOptions.NONE != null);
        assertTrue(SslBundleKey.NONE != null);
    }

    private static void generateLabCerts(Path out) throws Exception {
        Path script = Path.of("scripts/rabbitmq/generate-amqps-certs.sh");
        if (!Files.isRegularFile(script)) {
            // Maven cwd is often backend/
            Path alt = Path.of("..").resolve(script).normalize();
            if (Files.isRegularFile(alt)) {
                script = alt;
            }
        }
        ProcessBuilder pb = new ProcessBuilder("bash", script.toAbsolutePath().toString(), "--out", out.toString());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String log = new String(p.getInputStream().readAllBytes());
        boolean finished = p.waitFor(60, TimeUnit.SECONDS);
        assertTrue(finished, "cert script timed out");
        assertTrue(p.exitValue() == 0, "cert script failed: " + log);
        assertTrue(Files.isRegularFile(out.resolve("ca.crt")));
        assertTrue(Files.isRegularFile(out.resolve("client.crt")));
        assertTrue(Files.isRegularFile(out.resolve("server.crt")));
        assertTrue(Files.isRegularFile(out.resolve("rabbitmq.conf")));
    }

    private static final class FixedSslBundles implements SslBundles {
        private final SslBundle rabbitmq;

        private FixedSslBundles(SslBundle rabbitmq) {
            this.rabbitmq = rabbitmq;
        }

        @Override
        public SslBundle getBundle(String name) throws NoSuchSslBundleException {
            if ("rabbitmq".equals(name) && rabbitmq != null) {
                return rabbitmq;
            }
            throw new NoSuchSslBundleException(name, "Unknown bundle '" + name + "'");
        }

        @Override
        public void addBundleUpdateHandler(String name, Consumer<SslBundle> updateHandler)
                throws NoSuchSslBundleException {
            getBundle(name);
        }

        @Override
        public java.util.List<String> getBundleNames() {
            return rabbitmq == null ? java.util.List.of() : java.util.List.of("rabbitmq");
        }
    }
}
