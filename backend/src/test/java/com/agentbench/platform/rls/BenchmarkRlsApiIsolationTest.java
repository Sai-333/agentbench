package com.agentbench.platform.rls;

import com.agentbench.support.PostgresTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
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
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Postgres RLS on {@code benchmarks.benchmarks} (not {@code projects.projects}).
 * Rows are created through the API. An organization cannot read the other
 * organization's rows, including when a query omits the application org filter.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class BenchmarkRlsApiIsolationTest {

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
    NamedParameterJdbcTemplate jdbc;

    @Test
    void organizationCannotReadAnotherOrganizationsBenchmarks() throws Exception {
        String tokenA = register("rls-bench-a");
        String tokenB = register("rls-bench-b");
        UUID orgA = createOrg(tokenA);
        UUID orgB = createOrg(tokenB);
        UUID benchmarkA = createBenchmark(tokenA, orgA, "Bench A");
        UUID benchmarkB = createBenchmark(tokenB, orgB, "Bench B");

        JsonNode listed = listBenchmarks(tokenA, orgA);
        if (!containsId(listed, benchmarkA) || containsId(listed, benchmarkB)) {
            throw new AssertionError("organization list included another organization's benchmark");
        }
        assertStatus(getBenchmark(tokenA, orgA, benchmarkA), 200);
        assertStatus(getBenchmark(tokenA, orgA, benchmarkB), 404);
        assertProblemCode(getBenchmark(tokenA, orgB, benchmarkB), 403, "AUTH_FORBIDDEN");
        assertProblemCode(listRequest(tokenA, orgB), 403, "AUTH_FORBIDDEN");

        RlsContext.runWithOrg(orgA, () -> {
            List<UUID> other = selectBenchmark(benchmarkB);
            if (!other.isEmpty()) {
                throw new AssertionError("RLS exposed another organization's benchmark row");
            }
            List<UUID> own = selectBenchmark(benchmarkA);
            if (!List.of(benchmarkA).equals(own)) {
                throw new AssertionError("RLS hid the organization's own benchmark row");
            }
        });

        RlsContext.clear();
        Integer visible = jdbc.queryForObject(
                "SELECT COUNT(*) FROM benchmarks.benchmarks",
                Map.of(),
                Integer.class);
        if (visible == null || visible != 0) {
            throw new AssertionError("benchmark rows were visible without an organization");
        }
    }

    private List<UUID> selectBenchmark(UUID benchmarkId) {
        return jdbc.query(
                "SELECT id FROM benchmarks.benchmarks WHERE id = :id",
                new MapSqlParameterSource("id", benchmarkId),
                (rs, rowNum) -> rs.getObject("id", UUID.class));
    }

    private String register(String label) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "email", label + "-" + UUID.randomUUID() + "@example.com",
                                "password", "password123",
                                "displayName", label))))
                .andReturn();
        assertStatus(result, 201);
        return read(result).path("accessToken").asText();
    }

    private UUID createOrg(String token) throws Exception {
        String slug = "rls-" + UUID.randomUUID().toString().substring(0, 8);
        MvcResult result = mockMvc.perform(post("/api/v1/orgs")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "RLS " + slug, "slug", slug))))
                .andReturn();
        assertStatus(result, 201);
        return UUID.fromString(read(result).path("id").asText());
    }

    private UUID createBenchmark(String token, UUID orgId, String name) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/orgs/{orgId}/benchmarks", orgId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", name, "description", "isolated"))))
                .andReturn();
        assertStatus(result, 201);
        JsonNode body = read(result);
        if (!orgId.toString().equals(body.path("orgId").asText())) {
            throw new AssertionError("benchmark was not created in the requested organization");
        }
        return UUID.fromString(body.path("id").asText());
    }

    private JsonNode listBenchmarks(String token, UUID orgId) throws Exception {
        MvcResult result = listRequest(token, orgId);
        assertStatus(result, 200);
        JsonNode body = read(result);
        if (!body.isArray()) {
            throw new AssertionError("benchmark list was not an array");
        }
        return body;
    }

    private MvcResult listRequest(String token, UUID orgId) throws Exception {
        return mockMvc.perform(get("/api/v1/orgs/{orgId}/benchmarks", orgId)
                        .header("Authorization", "Bearer " + token))
                .andReturn();
    }

    private MvcResult getBenchmark(String token, UUID orgId, UUID benchmarkId) throws Exception {
        return mockMvc.perform(get("/api/v1/orgs/{orgId}/benchmarks/{benchmarkId}", orgId, benchmarkId)
                        .header("Authorization", "Bearer " + token))
                .andReturn();
    }

    private static boolean containsId(JsonNode listed, UUID id) {
        for (JsonNode item : listed) {
            if (id.toString().equals(item.path("id").asText())) {
                return true;
            }
        }
        return false;
    }

    private void assertProblemCode(MvcResult result, int status, String code) throws Exception {
        assertStatus(result, status);
        if (!code.equals(read(result).path("code").asText())) {
            throw new AssertionError("expected error code " + code);
        }
    }

    private static void assertStatus(MvcResult result, int expected) {
        int actual = result.getResponse().getStatus();
        if (actual != expected) {
            throw new AssertionError("expected HTTP " + expected + " but got " + actual);
        }
    }

    private JsonNode read(MvcResult result) throws Exception {
        String body = result.getResponse().getContentAsString();
        if (body == null || body.isBlank()) {
            return objectMapper.createObjectNode();
        }
        return objectMapper.readTree(body);
    }

    private byte[] json(Map<String, String> body) throws Exception {
        return objectMapper.writeValueAsBytes(body);
    }
}
