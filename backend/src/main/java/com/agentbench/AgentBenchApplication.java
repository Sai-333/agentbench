package com.agentbench;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
@EnableScheduling
public class AgentBenchApplication {

    static {
        // Checkstyle and PMD bring Saxon, whose service file would replace the JDK
        // transformer. Keep the platform factory for SAML and the rest of the app.
        if (System.getProperty("javax.xml.transform.TransformerFactory") == null) {
            System.setProperty(
                    "javax.xml.transform.TransformerFactory",
                    "com.sun.org.apache.xalan.internal.xsltc.trax.TransformerFactoryImpl");
        }
    }

    public static void main(String[] args) {
        SpringApplication.run(AgentBenchApplication.class, args);
    }
}
