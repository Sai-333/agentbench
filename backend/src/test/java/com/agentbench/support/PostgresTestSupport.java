package com.agentbench.support;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Shared Testcontainers Postgres property wiring for RLS-aware runs:
 * Flyway uses the bootstrap superuser; the app pool uses {@code agentbench_app}.
 */
public final class PostgresTestSupport {

    public static final String APP_USER = "agentbench_app";
    public static final String APP_PASSWORD = "agentbench";

    private PostgresTestSupport() {
    }

    public static PostgreSQLContainer<?> postgres() {
        return new PostgreSQLContainer<>("postgres:16-alpine")
                .withDatabaseName("agentbench")
                .withUsername("agentbench")
                .withPassword("agentbench")
                // Role must exist before Hikari validates the app DataSource (before Flyway).
                .withInitScript("db/test-init-app-role.sql");
    }

    public static void registerRlsAwareDatasource(
            DynamicPropertyRegistry registry,
            PostgreSQLContainer<?> postgres) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", () -> APP_USER);
        registry.add("spring.datasource.password", () -> APP_PASSWORD);
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.flyway.url", postgres::getJdbcUrl);
        registry.add("spring.flyway.user", postgres::getUsername);
        registry.add("spring.flyway.password", postgres::getPassword);
        registry.add("spring.autoconfigure.exclude", () -> String.join(",",
                "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration",
                "org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration",
                "org.springframework.boot.autoconfigure.amqp.RabbitAutoConfiguration"));
        registry.add("management.health.redis.enabled", () -> "false");
        registry.add("management.health.rabbit.enabled", () -> "false");
        registry.add("spring.rabbitmq.listener.simple.auto-startup", () -> "false");
        registry.add("agentbench.jwt.secret", () -> "test-only-jwt-secret-key-32chars-min!!");
        registry.add("agentbench.secrets.encryption-key", () -> "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=");
        registry.add("agentbench.github.validation-enabled", () -> "false");
        registry.add("agentbench.artifacts.root", () -> System.getProperty("java.io.tmpdir") + "/agentbench-it");
        registry.add("logging.structured.format.console", () -> "");
    }
}
