package com.agentbench.platform.gpu;

import com.agentbench.support.PostgresTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The POST uses the real local graphics probe. A machine without a GPU must
 * store an explicit unavailable row and a null score. Schema checks reject a
 * measured row with no frame time and an unavailable row that carries one.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class GpuGraphicsIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = PostgresTestSupport.postgres();

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        PostgresTestSupport.registerRlsAwareDatasource(registry, postgres);
    }

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void anonymousCannotRunOrList() throws Exception {
        mockMvc.perform(get("/api/v1/gpu-graphics")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/gpu-graphics")).andExpect(status().isUnauthorized());
    }

    @Test
    void viewerIsForbiddenAndAdminStoresAnExplicitProbe() throws Exception {
        String owner = register("owner-" + UUID.randomUUID() + "@example.com", "Owner");
        String viewer = register("viewer-" + UUID.randomUUID() + "@example.com", "Viewer");
        UUID orgId = createOrg(owner, "GFX " + UUID.randomUUID(), "gfx-" + UUID.randomUUID().toString().substring(0, 8));
        addMember(owner, orgId, meEmail(viewer), "VIEWER");

        mockMvc.perform(post("/api/v1/gpu-graphics").header("Authorization", "Bearer " + viewer))
                .andExpect(status().isForbidden());

        MvcResult created = mockMvc.perform(post("/api/v1/gpu-graphics")
                        .header("Authorization", "Bearer " + owner))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode body = objectMapper.readTree(created.getResponse().getContentAsString());
        assertExplicitProbe(body);
        String id = body.get("id").asText();

        MvcResult fetched = mockMvc.perform(get("/api/v1/gpu-graphics/{id}", id)
                        .header("Authorization", "Bearer " + owner))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode again = objectMapper.readTree(fetched.getResponse().getContentAsString());
        assertEquals(body, again);

        MvcResult list = mockMvc.perform(get("/api/v1/gpu-graphics")
                        .header("Authorization", "Bearer " + owner))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode rows = objectMapper.readTree(list.getResponse().getContentAsString());
        boolean found = false;
        for (JsonNode row : rows) {
            if (id.equals(row.get("id").asText())) {
                found = true;
                assertExplicitProbe(row);
            }
        }
        assertTrue(found);
    }

    @Test
    void schemaRejectsAScoreThatDoesNotMatchTheStatus() {
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("""
                INSERT INTO platform.gpu_graphics_results (
                    status, device_name, frames, frame_time_ms, score, score_unit,
                    unavailable_reason, probe_name
                ) VALUES ('measured', 'graphics-constraint', 32, NULL, NULL, 'ms', NULL,
                          'agentbench-gpu-graphics-v1')
                """));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("""
                INSERT INTO platform.gpu_graphics_results (
                    status, device_name, frames, frame_time_ms, score, score_unit,
                    unavailable_reason, probe_name
                ) VALUES ('unavailable', NULL, NULL, 1.25, 1.25, NULL, 'no_gpu',
                          'agentbench-gpu-graphics-v1')
                """));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("""
                INSERT INTO platform.gpu_graphics_results (
                    status, device_name, frames, frame_time_ms, score, score_unit,
                    unavailable_reason, probe_name
                ) VALUES ('measured', 'graphics-constraint', 32, 1.25, 2.00, 'ms', NULL,
                          'agentbench-gpu-graphics-v1')
                """));
        jdbc.update("""
                INSERT INTO platform.gpu_graphics_results (
                    status, device_name, frames, frame_time_ms, score, score_unit,
                    unavailable_reason, probe_name
                ) VALUES ('measured', 'graphics-constraint', 32, 1.25, 1.25, 'ms', NULL,
                          'agentbench-gpu-graphics-v1')
                """);
        try {
            BigDecimal score = jdbc.queryForObject(
                    "SELECT score FROM platform.gpu_graphics_results WHERE device_name = 'graphics-constraint'",
                    BigDecimal.class);
            Integer frames = jdbc.queryForObject(
                    "SELECT frames FROM platform.gpu_graphics_results WHERE device_name = 'graphics-constraint'",
                    Integer.class);
            assertEquals(0, new BigDecimal("1.25").compareTo(score));
            assertEquals(32, frames);
        } finally {
            jdbc.update("DELETE FROM platform.gpu_graphics_results WHERE device_name = 'graphics-constraint'");
        }
    }

    private static void assertExplicitProbe(JsonNode body) {
        String blob = body.toString().toLowerCase();
        assertFalse(blob.contains("3dmark"));
        assertFalse(blob.contains("ul solutions"));
        assertFalse(blob.contains("certified"));
        assertFalse(blob.contains("certification"));
        assertEquals(GpuGraphicsResult.PROBE_NAME, body.get("probe").asText());
        String status = body.get("status").asText();
        if ("measured".equals(status)) {
            assertFalse(body.get("device").asText().isBlank());
            assertEquals(GpuGraphicsScores.FRAMES, body.get("frames").asInt());
            assertTrue(body.get("frameTimeMs").decimalValue().signum() > 0);
            assertEquals(0, body.get("frameTimeMs").decimalValue().compareTo(body.get("score").decimalValue()));
            assertEquals("ms", body.get("scoreUnit").asText());
            assertTrue(body.get("unavailableReason").isNull());
        } else {
            assertEquals("unavailable", status);
            assertTrue(body.get("score").isNull());
            assertTrue(body.get("frameTimeMs").isNull());
            assertTrue(body.get("frames").isNull());
            assertTrue(body.get("scoreUnit").isNull());
            String reason = body.get("unavailableReason").asText();
            assertTrue("no_gpu".equals(reason) || "probe_failed".equals(reason));
            if ("no_gpu".equals(reason)) {
                assertTrue(body.get("device").isNull());
            }
        }
    }

    private String register(String email, String displayName) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType("application/json")
                        .content("""
                                {"email":"%s","password":"password123","displayName":"%s"}
                                """.formatted(email, displayName)))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("accessToken").asText();
    }

    private UUID createOrg(String token, String name, String slug) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/orgs")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content("""
                                {"name":"%s","slug":"%s"}
                                """.formatted(name, slug)))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText());
    }

    private void addMember(String token, UUID orgId, String email, String role) throws Exception {
        mockMvc.perform(post("/api/v1/orgs/{orgId}/members", orgId)
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content("""
                                {"email":"%s","role":"%s"}
                                """.formatted(email, role)))
                .andExpect(status().isCreated());
    }

    private String meEmail(String token) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/me")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("email").asText();
    }
}
