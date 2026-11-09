package com.agentbench.platform.api;

import com.agentbench.identity.security.JwtAuthenticationFilter;
import com.agentbench.identity.security.JwtService;
import com.agentbench.platform.config.SecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = PlatformInfoController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, JwtService.class})
@TestPropertySource(properties = {
        "logging.structured.format.console=",
        "agentbench.jwt.secret=test-only-jwt-secret-key-32chars-min!!"
})
class PlatformInfoControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void infoExposesOrgScopedApiConvention() throws Exception {
        mockMvc.perform(get("/api/v1/info"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("agentbench"))
                .andExpect(jsonPath("$.milestone").value("M6"))
                .andExpect(jsonPath("$.orgScopedApiBase", containsString("/api/v1/orgs/{orgId}/")));
    }
}
