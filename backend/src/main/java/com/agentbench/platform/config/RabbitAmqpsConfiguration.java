package com.agentbench.platform.config;

import org.springframework.amqp.rabbit.connection.RabbitConnectionFactoryBean;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.amqp.RabbitProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

/**
 * Fail-fast checks when opt-in RabbitMQ AMQPS (TLS) is enabled.
 *
 * <p>Trust material comes from Spring Boot SSL properties:
 * <ul>
 *   <li>PEM bundle (preferred for Compose/Helm): {@code spring.rabbitmq.ssl.bundle=rabbitmq}
 *       plus {@code spring.ssl.bundle.pem.rabbitmq.truststore.certificate} (and optional
 *       keystore certificate/private-key for mutual TLS), or</li>
 *   <li>Classic keystore/truststore locations under {@code spring.rabbitmq.ssl.*-store}.</li>
 * </ul>
 *
 * <p>Default is off — Mac Compose stays plain AMQP on 5672. Not a service mesh.
 */
@Configuration
@ConditionalOnProperty(prefix = "spring.rabbitmq.ssl", name = "enabled", havingValue = "true")
public class RabbitAmqpsConfiguration {

    @Bean
    ApplicationRunner rabbitAmqpsStartupCheck(RabbitProperties rabbitProperties,
                                              org.springframework.beans.factory.ObjectProvider<SslBundles> sslBundles) {
        return args -> validateAmqpsConfig(rabbitProperties, sslBundles.getIfAvailable());
    }

    /**
     * Package-visible for unit tests.
     */
    static void validateAmqpsConfig(RabbitProperties rabbitProperties, SslBundles sslBundles) {
        RabbitProperties.Ssl ssl = rabbitProperties.getSsl();
        if (ssl == null || !Boolean.TRUE.equals(ssl.getEnabled())) {
            return;
        }

        String bundle = ssl.getBundle();
        if (StringUtils.hasText(bundle)) {
            if (sslBundles == null) {
                throw new IllegalStateException(
                        "spring.rabbitmq.ssl.enabled=true with bundle='" + bundle
                                + "' but no SslBundles bean is available");
            }
            try {
                sslBundles.getBundle(bundle);
            } catch (RuntimeException ex) {
                throw new IllegalStateException(
                        "AMQPS enabled but SSL bundle '" + bundle + "' is missing or invalid: "
                                + ex.getMessage(),
                        ex);
            }
            return;
        }

        if (!StringUtils.hasText(ssl.getTrustStore())) {
            throw new IllegalStateException(
                    "AMQPS enabled (spring.rabbitmq.ssl.enabled=true) but neither "
                            + "spring.rabbitmq.ssl.bundle nor spring.rabbitmq.ssl.trust-store is set. "
                            + "See docs/DEPLOYMENT.md §3f.");
        }
    }

    /**
     * Expose whether the Rabbit client would use SSL given current properties.
     * Useful for tests / ops smoke without opening a broker connection.
     */
    static boolean isSslConfigured(RabbitProperties rabbitProperties) {
        if (rabbitProperties == null || rabbitProperties.getSsl() == null) {
            return false;
        }
        return rabbitProperties.getSsl().determineEnabled();
    }

    /**
     * Documents the classic keystore property names expected by
     * {@link RabbitConnectionFactoryBean} when not using an SSL bundle.
     */
    static boolean hasClassicTrustStore(RabbitProperties.Ssl ssl) {
        return ssl != null && StringUtils.hasText(ssl.getTrustStore());
    }
}
