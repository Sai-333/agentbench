package com.agentbench.platform.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class OpenApiConfig {

    @Bean
    OpenAPI agentBenchOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("AgentBench API")
                        .description("""
                                Modular monolith API for AgentBench.

                                Tenant-scoped endpoints use `/api/v1/orgs/{orgId}/...`.
                                Milestone 1 exposes platform health/info and OpenAPI only.
                                """)
                        .version("v1"))
                .servers(List.of(new Server().url("/").description("Current host")));
    }
}
