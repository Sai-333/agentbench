package com.agentbench.execution.quality;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SonarQualityAnalyzerTest {

    @RegisterExtension
    static WireMockExtension sonar = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    @TempDir
    Path temp;

    @Test
    void disabledByDefaultReturnsStub() {
        QualityToolReport report = SonarQualityAnalyzer.disabled().analyze(temp);
        assertTrue(report.stub());
        assertEquals("sonar_disabled", report.notes());
    }

    @Test
    void prefersWorkspaceReportImportOverApi() throws Exception {
        Path ws = temp.resolve("ws");
        Files.createDirectories(ws.resolve("target"));
        Files.writeString(ws.resolve("target/sonar-issues.json"), """
                {"issues":[{"rule":"java:S106","severity":"MINOR","component":"demo:A.java","line":3,
                "message":"logger","status":"OPEN"}]}
                """, StandardCharsets.UTF_8);

        SonarProperties props = new SonarProperties();
        props.setEnabled(true);
        props.setBaseUrl(sonar.baseUrl());
        props.setToken("token");
        props.setProjectKey("demo");

        SonarQualityAnalyzer analyzer = new SonarQualityAnalyzer(props, new SonarQualityClient(new ObjectMapper()));
        QualityToolReport report = analyzer.analyze(ws);
        assertFalse(report.stub());
        assertEquals(1, report.findings().size());
        assertTrue(report.notes().startsWith("imported:"));
        sonar.verify(0, getRequestedFor(urlPathEqualTo("/api/issues/search")));
    }

    @Test
    void fetchesApiWhenEnabledAndNoLocalReport() throws Exception {
        Path empty = temp.resolve("empty");
        Files.createDirectories(empty);

        sonar.stubFor(get(urlPathEqualTo("/api/issues/search"))
                .withQueryParam("componentKeys", equalTo("demo"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"total":1,"issues":[{"rule":"java:S1","severity":"MAJOR",
                                "component":"demo:B.java","line":1,"message":"x","status":"OPEN"}]}
                                """)));

        SonarProperties props = new SonarProperties();
        props.setEnabled(true);
        props.setBaseUrl(sonar.baseUrl());
        props.setToken("token");
        props.setProjectKey("demo");

        QualityToolReport report = new SonarQualityAnalyzer(props, new SonarQualityClient(new ObjectMapper()))
                .analyze(empty);
        assertFalse(report.stub());
        assertEquals(1, report.findings().size());
        assertTrue(report.notes().startsWith("sonar_api_fetch:"));
    }
}
