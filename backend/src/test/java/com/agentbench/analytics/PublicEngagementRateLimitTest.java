package com.agentbench.analytics;

import com.agentbench.support.PostgresTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * REG-RATE-LIMIT HTTP contract: the 6th comment and the 31st vote from one client
 * key inside the default 600 second window return 429 {@code RATE_LIMITED}.
 * A different client key is a different window in the same process.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class PublicEngagementRateLimitTest {

    private static final String SLUG = "missing-rate-limit-board";
    private static final String COMMENTER = "203.0.113.20";
    private static final String OTHER = "203.0.113.21";

    @Container
    static PostgreSQLContainer<?> postgres = PostgresTestSupport.postgres();

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        PostgresTestSupport.registerRlsAwareDatasource(registry, postgres);
        registry.add("agentbench.evaluation.listener-enabled", () -> "false");
        registry.add("agentbench.execution.mode", () -> "local");
        registry.add("agentbench.execution.docker-enabled", () -> "false");
    }

    @Autowired
    MockMvc mockMvc;

    @Test
    void sixthCommentAndThirtyFirstVoteReturn429() throws Exception {
        for (int i = 0; i < 5; i++) {
            postComment(COMMENTER).andExpect(status().isNotFound());
        }
        postComment(COMMENTER)
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"))
                .andExpect(jsonPath("$.status").value(429))
                .andExpect(jsonPath("$.detail").value(containsString("comment")));

        for (int i = 0; i < 30; i++) {
            postVote(COMMENTER).andExpect(status().isNotFound());
        }
        postVote(COMMENTER)
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"))
                .andExpect(jsonPath("$.status").value(429))
                .andExpect(jsonPath("$.detail").value(containsString("vote")));

        postComment(OTHER).andExpect(status().isNotFound());
        postVote(OTHER).andExpect(status().isNotFound());
    }

    private ResultActions postComment(String client) throws Exception {
        return mockMvc.perform(post("/api/v1/public/leaderboards/{slug}/comments", SLUG)
                .header("X-Forwarded-For", client)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"body":"hello there","displayName":"Guest"}
                        """));
    }

    private ResultActions postVote(String client) throws Exception {
        return mockMvc.perform(post("/api/v1/public/leaderboards/{slug}/votes", SLUG)
                .header("X-Forwarded-For", client)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"value":1,"voterFingerprint":"anonfp12345678"}
                        """));
    }
}
