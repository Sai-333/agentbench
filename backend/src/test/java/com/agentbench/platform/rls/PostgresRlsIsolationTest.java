package com.agentbench.platform.rls;

import com.agentbench.support.PostgresTestSupport;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Proves Postgres FORCE RLS blocks cross-org SELECT/UPDATE/DELETE even when a
 * buggy repository query omits the application-layer {@code org_id} filter.
 * Runs as non-superuser {@code agentbench_app}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class PostgresRlsIsolationTest {

    @Container
    static PostgreSQLContainer<?> postgres = PostgresTestSupport.postgres();

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        PostgresTestSupport.registerRlsAwareDatasource(registry, postgres);
    }

    @Autowired
    MockMvc mockMvc;

    @Autowired
    NamedParameterJdbcTemplate jdbc;

    @Test
    void rlsBlocksCrossOrgSelectUpdateDeleteWithoutOrgFilter() throws Exception {
        String tokenA = register("rls-a-" + UUID.randomUUID() + "@example.com", "RlsA");
        String tokenB = register("rls-b-" + UUID.randomUUID() + "@example.com", "RlsB");
        UUID orgA = createOrg(tokenA, "RLS Org A " + UUID.randomUUID(), "rls-a-" + UUID.randomUUID().toString().substring(0, 8));
        UUID orgB = createOrg(tokenB, "RLS Org B " + UUID.randomUUID(), "rls-b-" + UUID.randomUUID().toString().substring(0, 8));
        UUID projectA = createProject(tokenA, orgA, "ProjectA");
        UUID projectB = createProject(tokenB, orgB, "ProjectB");

        // Buggy SELECT by id only — must not see org B while session is org A.
        RlsContext.runWithOrg(orgA, () -> {
            List<UUID> leaked = jdbc.query(
                    "SELECT id FROM projects.projects WHERE id = :id",
                    new MapSqlParameterSource("id", projectB),
                    (rs, rowNum) -> rs.getObject("id", UUID.class));
            assertTrue(leaked.isEmpty(), "RLS must hide other-org rows from SELECT without org filter");

            List<UUID> own = jdbc.query(
                    "SELECT id FROM projects.projects WHERE id = :id",
                    new MapSqlParameterSource("id", projectA),
                    (rs, rowNum) -> rs.getObject("id", UUID.class));
            assertEquals(List.of(projectA), own);
        });

        // Buggy UPDATE by id only — must affect 0 rows for cross-org target.
        RlsContext.runWithOrg(orgA, () -> {
            int updated = jdbc.update(
                    "UPDATE projects.projects SET description = 'pwned' WHERE id = :id",
                    new MapSqlParameterSource("id", projectB));
            assertEquals(0, updated, "RLS must block cross-org UPDATE without org filter");
        });
        RlsContext.runWithOrg(orgB, () -> {
            String description = jdbc.queryForObject(
                    "SELECT description FROM projects.projects WHERE id = :id",
                    new MapSqlParameterSource("id", projectB),
                    String.class);
            assertEquals("demo", description);
        });

        // Buggy DELETE by id only — must affect 0 rows for cross-org target.
        RlsContext.runWithOrg(orgA, () -> {
            int deleted = jdbc.update(
                    "DELETE FROM projects.projects WHERE id = :id",
                    new MapSqlParameterSource("id", projectB));
            assertEquals(0, deleted, "RLS must block cross-org DELETE without org filter");
        });
        RlsContext.runWithOrg(orgB, () -> {
            Integer count = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM projects.projects WHERE id = :id",
                    new MapSqlParameterSource("id", projectB),
                    Integer.class);
            assertEquals(1, count);
        });

        // Without org GUC and without bypass, tenant tables are empty.
        RlsContext.clear();
        Integer visible = jdbc.queryForObject(
                "SELECT COUNT(*) FROM projects.projects",
                Map.of(),
                Integer.class);
        assertEquals(0, visible, "No tenant rows without app.current_org_id or bypass");
    }

    private String register(String email, String displayName) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"password123","displayName":"%s"}
                                """.formatted(email, displayName)))
                .andExpect(status().isCreated())
                .andReturn();
        return new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(result.getResponse().getContentAsString())
                .get("accessToken")
                .asText();
    }

    private UUID createOrg(String token, String name, String slug) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/orgs")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"%s","slug":"%s"}
                                """.formatted(name, slug)))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(result.getResponse().getContentAsString())
                .get("id")
                .asText());
    }

    private UUID createProject(String token, UUID orgId, String name) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/orgs/{orgId}/projects", orgId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"%s","description":"demo"}
                                """.formatted(name)))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(result.getResponse().getContentAsString())
                .get("id")
                .asText());
    }
}
