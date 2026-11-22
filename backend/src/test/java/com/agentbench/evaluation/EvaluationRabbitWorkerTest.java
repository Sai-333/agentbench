package com.agentbench.evaluation;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.agentbench.evaluation.domain.EvaluationState;
import com.agentbench.evaluation.service.EvaluationCommand;
import com.agentbench.evaluation.service.EvaluationStageProcessor;
import com.agentbench.evaluation.worker.EvaluationDlqListener;
import com.agentbench.evaluation.worker.EvaluationOutboxPublisher;
import com.agentbench.platform.config.RabbitConfig;
import com.agentbench.platform.rls.RlsContext;
import com.agentbench.support.PostgresTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.GetResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.QueueInformation;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.AbstractMessageListenerContainer;
import org.springframework.amqp.rabbit.listener.MessageListenerContainer;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * REG-RABBIT-WORKER: outbox publish onto {@code evaluation.commands.q}, consumption by
 * {@link com.agentbench.evaluation.worker.EvaluationCommandListener}, duplicate ignore via
 * {@code evaluations.processed_messages}, and a poison message on {@code evaluation.commands.dlq}.
 * Does not replace {@link com.agentbench.evaluation.service.EvaluationSyncRunner}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class EvaluationRabbitWorkerTest {

    @Container
    static PostgreSQLContainer<?> postgres = PostgresTestSupport.postgres();

    @Container
    static RabbitMQContainer rabbit = new RabbitMQContainer(
            DockerImageName.parse("rabbitmq:3.13-management-alpine"))
            .withAdminUser("agentbench")
            .withAdminPassword("agentbench")
            .withStartupTimeout(Duration.ofMinutes(3));

    @DynamicPropertySource
    static void brokerProps(DynamicPropertyRegistry registry) {
        PostgresTestSupport.registerRlsAwareDatasource(registry, postgres);
        registry.add("spring.autoconfigure.exclude", () -> String.join(",",
                "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration",
                "org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration"));
        registry.add("spring.rabbitmq.host", rabbit::getHost);
        registry.add("spring.rabbitmq.port", rabbit::getAmqpPort);
        registry.add("spring.rabbitmq.username", rabbit::getAdminUsername);
        registry.add("spring.rabbitmq.password", rabbit::getAdminPassword);
        registry.add("spring.rabbitmq.listener.simple.auto-startup", () -> "true");
        registry.add("spring.rabbitmq.listener.simple.default-requeue-rejected", () -> "false");
        registry.add("spring.rabbitmq.listener.simple.retry.enabled", () -> "true");
        registry.add("spring.rabbitmq.listener.simple.retry.initial-interval", () -> "20ms");
        registry.add("spring.rabbitmq.listener.simple.retry.multiplier", () -> "1");
        registry.add("spring.rabbitmq.listener.simple.retry.max-attempts", () -> "2");
        registry.add("agentbench.evaluation.listener-enabled", () -> "true");
        registry.add("agentbench.evaluation.outbox-poll-ms", () -> "600000");
        registry.add("agentbench.execution.mode", () -> "local");
        registry.add("agentbench.execution.docker-enabled", () -> "false");
        registry.add("management.health.rabbit.enabled", () -> "false");
    }

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    NamedParameterJdbcTemplate jdbc;

    @Autowired
    EvaluationOutboxPublisher outboxPublisher;

    @Autowired
    RabbitTemplate rabbitTemplate;

    @Autowired
    RabbitAdmin rabbitAdmin;

    @Autowired
    RabbitListenerEndpointRegistry listenerRegistry;

    @AfterEach
    void restartCommandListener() {
        findCommandContainer().ifPresent(container -> {
            if (!container.isRunning()) {
                container.start();
            }
        });
    }

    @Test
    void outboxPublishesToCommandQueueAndDuplicateIsIgnored() throws Exception {
        assertEquals("evaluation.commands", RabbitConfig.EVALUATION_EXCHANGE);
        assertEquals("evaluation.commands.q", RabbitConfig.EVALUATION_QUEUE);
        assertEquals("evaluation.advance", RabbitConfig.EVALUATION_ROUTING_KEY);

        AbstractMessageListenerContainer commands = awaitCommandContainer();
        commands.stop();
        await().atMost(15, SECONDS).until(() -> !commands.isRunning());
        await().atMost(15, SECONDS).until(() -> queue(RabbitConfig.EVALUATION_QUEUE).getConsumerCount() == 0);

        String token = register("rabbit-" + UUID.randomUUID() + "@example.com");
        UUID orgId = createOrg(token);
        UUID versionId = createVersion(token, orgId);
        UUID taskId = createTask(token, orgId, versionId);
        upsertVisible(token, orgId, taskId);
        mockMvc.perform(post("/api/v1/orgs/{orgId}/benchmark-versions/{versionId}/publish", orgId, versionId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        UUID agentId = createFakeAgent(token, orgId);
        UUID evaluationId = enqueue(token, orgId, versionId, taskId, agentId);

        String payload = outboxPayload(evaluationId);
        EvaluationCommand command = objectMapper.readValue(payload, EvaluationCommand.class);
        assertEquals(1, markCompleted(evaluationId));
        assertEquals("COMPLETED", evaluationState(evaluationId));

        assertEquals(1, outboxPublisher.drain(10, true));
        assertTrue(published(evaluationId));
        QueueInformation queued = queue(RabbitConfig.EVALUATION_QUEUE);
        assertEquals(1, queued.getMessageCount());

        String body = peekRequeue(RabbitConfig.EVALUATION_QUEUE);
        assertNotNull(body);
        assertTrue(body.contains(command.messageId()));
        assertTrue(body.contains(EvaluationState.PREPARING_REPO.name()));
        assertEquals(1, queue(RabbitConfig.EVALUATION_QUEUE).getMessageCount());

        ListAppender<ILoggingEvent> skips = attach(EvaluationStageProcessor.class);
        try {
            commands.start();
            await().atMost(15, SECONDS).until(commands::isRunning);
            await().atMost(20, SECONDS).until(() -> processedCount(command.messageId()) == 1);
            assertEquals(orgId.toString(), processedOrg(command.messageId()));
            assertEquals("COMPLETED", evaluationState(evaluationId));
            assertEquals(0, stageAttempts(evaluationId));

            skips.list.clear();
            rabbitTemplate.convertAndSend(
                    RabbitConfig.EVALUATION_EXCHANGE, RabbitConfig.EVALUATION_ROUTING_KEY, command);
            await().atMost(20, SECONDS).until(() -> skips.list.stream()
                    .anyMatch(event -> event.getFormattedMessage()
                            .contains("Skipping duplicate message " + command.messageId())));
            assertEquals(1, processedCount(command.messageId()));
            assertEquals("COMPLETED", evaluationState(evaluationId));
            assertEquals(0, stageAttempts(evaluationId));
        } finally {
            detach(EvaluationStageProcessor.class, skips);
        }
    }

    @Test
    void poisonMessageLandsOnDlqAndIsNotRequeued() throws Exception {
        assertEquals("evaluation.commands.dlq", RabbitConfig.EVALUATION_DLQ);
        AbstractMessageListenerContainer commands = awaitCommandContainer();
        if (!commands.isRunning()) {
            commands.start();
        }
        await().atMost(15, SECONDS).until(commands::isRunning);

        String messageId = "poison-" + UUID.randomUUID();
        EvaluationCommand poison = new EvaluationCommand(
                messageId,
                UUID.randomUUID(),
                UUID.randomUUID(),
                EvaluationState.PREPARING_REPO,
                1);
        ListAppender<ILoggingEvent> deadLetters = attach(EvaluationDlqListener.class);
        try {
            rabbitTemplate.convertAndSend(
                    RabbitConfig.EVALUATION_EXCHANGE, RabbitConfig.EVALUATION_ROUTING_KEY, poison);
            await().atMost(20, SECONDS).until(() -> deadLetterCount(deadLetters, messageId) == 1);
            String logged = deadLetters.list.stream()
                    .map(ILoggingEvent::getFormattedMessage)
                    .filter(message -> message.contains(messageId))
                    .findFirst()
                    .orElseThrow();
            assertTrue(logged.contains("dead-lettered"));
            assertTrue(logged.contains("x-death="));
            assertFalse(logged.contains("x-death=null"));
            await().atMost(10, SECONDS).until(() ->
                    queue(RabbitConfig.EVALUATION_QUEUE).getMessageCount() == 0
                            && queue(RabbitConfig.EVALUATION_DLQ).getMessageCount() == 0);
            await().pollDelay(Duration.ofMillis(400)).atMost(2, SECONDS)
                    .until(() -> deadLetterCount(deadLetters, messageId) == 1);
            assertEquals(0, processedCount(messageId));
        } finally {
            detach(EvaluationDlqListener.class, deadLetters);
        }
    }

    private static long deadLetterCount(ListAppender<ILoggingEvent> appender, String messageId) {
        return appender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .filter(message -> message.contains("dead-lettered") && message.contains(messageId))
                .count();
    }

    private AbstractMessageListenerContainer awaitCommandContainer() {
        await().atMost(20, SECONDS).until(() -> findCommandContainer().isPresent());
        AbstractMessageListenerContainer container = findCommandContainer().orElseThrow();
        if (!container.isRunning()) {
            container.start();
            await().atMost(15, SECONDS).until(container::isRunning);
        }
        return container;
    }

    private Optional<AbstractMessageListenerContainer> findCommandContainer() {
        for (MessageListenerContainer container : listenerRegistry.getListenerContainers()) {
            if (container instanceof AbstractMessageListenerContainer amlc) {
                String[] names = amlc.getQueueNames();
                if (names != null && Arrays.asList(names).contains(RabbitConfig.EVALUATION_QUEUE)) {
                    return Optional.of(amlc);
                }
            }
        }
        return Optional.empty();
    }

    private QueueInformation queue(String name) {
        QueueInformation info = rabbitAdmin.getQueueInfo(name);
        assertNotNull(info, name);
        return info;
    }

    private String peekRequeue(String queue) {
        return rabbitTemplate.execute(channel -> {
            GetResponse got = channel.basicGet(queue, false);
            if (got == null || got.getBody() == null) {
                return null;
            }
            String text = new String(got.getBody(), StandardCharsets.UTF_8);
            channel.basicNack(got.getEnvelope().getDeliveryTag(), false, true);
            return text;
        });
    }

    private ListAppender<ILoggingEvent> attach(Class<?> type) {
        Logger logger = (Logger) LoggerFactory.getLogger(type);
        logger.setLevel(Level.INFO);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }

    private void detach(Class<?> type, ListAppender<ILoggingEvent> appender) {
        Logger logger = (Logger) LoggerFactory.getLogger(type);
        logger.detachAppender(appender);
        appender.stop();
    }

    private String outboxPayload(UUID evaluationId) {
        return RlsContext.callWithBypass(() -> jdbc.queryForObject("""
                SELECT payload::text
                FROM evaluations.outbox_events
                WHERE aggregate_id = :id
                ORDER BY id ASC
                LIMIT 1
                """, new MapSqlParameterSource("id", evaluationId), String.class));
    }

    private boolean published(UUID evaluationId) {
        Boolean value = RlsContext.callWithBypass(() -> jdbc.queryForObject("""
                SELECT published_at IS NOT NULL
                FROM evaluations.outbox_events
                WHERE aggregate_id = :id
                ORDER BY id ASC
                LIMIT 1
                """, new MapSqlParameterSource("id", evaluationId), Boolean.class));
        return Boolean.TRUE.equals(value);
    }

    private int markCompleted(UUID evaluationId) {
        return RlsContext.callWithBypass(() -> jdbc.update("""
                UPDATE evaluations.evaluations
                SET state = 'COMPLETED', updated_at = now()
                WHERE id = :id
                """, new MapSqlParameterSource("id", evaluationId)));
    }

    private int processedCount(String messageId) {
        Integer count = RlsContext.callWithBypass(() -> jdbc.queryForObject("""
                SELECT COUNT(*) FROM evaluations.processed_messages WHERE message_id = :messageId
                """, new MapSqlParameterSource("messageId", messageId), Integer.class));
        return count == null ? 0 : count;
    }

    private String processedOrg(String messageId) {
        return RlsContext.callWithBypass(() -> jdbc.queryForObject("""
                SELECT org_id::text FROM evaluations.processed_messages WHERE message_id = :messageId
                """, new MapSqlParameterSource("messageId", messageId), String.class));
    }

    private String evaluationState(UUID evaluationId) {
        return RlsContext.callWithBypass(() -> jdbc.queryForObject("""
                SELECT state FROM evaluations.evaluations WHERE id = :id
                """, new MapSqlParameterSource("id", evaluationId), String.class));
    }

    private int stageAttempts(UUID evaluationId) {
        Integer count = RlsContext.callWithBypass(() -> jdbc.queryForObject("""
                SELECT COUNT(*) FROM evaluations.evaluation_stage_attempts WHERE evaluation_id = :id
                """, new MapSqlParameterSource("id", evaluationId), Integer.class));
        return count == null ? 0 : count;
    }

    private String register(String email) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"password123","displayName":"Rabbit Owner"}
                                """.formatted(email)))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("accessToken").asText();
    }

    private UUID createOrg(String token) throws Exception {
        String slug = "rb-" + UUID.randomUUID().toString().substring(0, 8);
        MvcResult result = mockMvc.perform(post("/api/v1/orgs")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Rabbit Org","slug":"%s"}
                                """.formatted(slug)))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText());
    }

    private UUID createVersion(String token, UUID orgId) throws Exception {
        MvcResult benchmark = mockMvc.perform(post("/api/v1/orgs/{orgId}/benchmarks", orgId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Rabbit Bench","description":"worker"}
                                """))
                .andExpect(status().isCreated())
                .andReturn();
        UUID benchmarkId = UUID.fromString(
                objectMapper.readTree(benchmark.getResponse().getContentAsString()).get("id").asText());
        MvcResult version = mockMvc.perform(post("/api/v1/orgs/{orgId}/benchmarks/{benchmarkId}/versions", orgId, benchmarkId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"version":"1.0.0","changelog":"worker"}
                                """))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(objectMapper.readTree(version.getResponse().getContentAsString()).get("id").asText());
    }

    private UUID createTask(String token, UUID orgId, UUID versionId) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/orgs/{orgId}/benchmark-versions/{versionId}/tasks", orgId, versionId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"slug":"rabbit-task","title":"Rabbit task","prompt":"Leave the file alone"}
                                """))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText());
    }

    private void upsertVisible(String token, UUID orgId, UUID taskId) throws Exception {
        mockMvc.perform(put("/api/v1/orgs/{orgId}/tasks/{taskId}/suites/visible", orgId, taskId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"framework":"fixture-v1","entryCommand":"fixture","fixtureContent":"{}","caseCount":0,"expectedOutcomes":{}}
                                """))
                .andExpect(status().isOk());
    }

    private UUID createFakeAgent(String token, UUID orgId) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/orgs/{orgId}/agents", orgId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Rabbit Fake","adapterType":"FAKE","config":{"patch":"diff --git a/A.java b/A.java"}}
                                """))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText());
    }

    private UUID enqueue(String token, UUID orgId, UUID versionId, UUID taskId, UUID agentId) throws Exception {
        MvcResult created = mockMvc.perform(post("/api/v1/orgs/{orgId}/evaluations", orgId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"benchmarkVersionId":"%s","taskId":"%s","agentId":"%s"}
                                """.formatted(versionId, taskId, agentId)))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode body = objectMapper.readTree(created.getResponse().getContentAsString());
        assertEquals("QUEUED", body.get("state").asText());
        return UUID.fromString(body.get("id").asText());
    }
}
