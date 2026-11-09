package com.agentbench;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;

@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
public class AgentBenchApplication {

    public static void main(String[] args) {
        SpringApplication.run(AgentBenchApplication.class, args);
    }
}
