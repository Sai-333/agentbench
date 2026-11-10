package com.agentbench.execution.quality;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SonarQualityClientTest {

    @RegisterExtension
    static WireMockExtension sonar = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    @Test
    void fetchesIssuesViaWireMockWithoutLiveSonar() throws Exception {
        String body = Files.readString(
                Path.of("src/test/resources/fixtures/sonar-issues-sample.json"),
                StandardCharsets.UTF_8);
        sonar.stubFor(get(urlPathEqualTo("/api/issues/search"))
                .withQueryParam("componentKeys", equalTo("agentbench-demo"))
                .withQueryParam("resolved", equalTo("false"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(body)));

        SonarProperties props = new SonarProperties();
        props.setEnabled(true);
        props.setBaseUrl(sonar.baseUrl());
        props.setToken("test-sonar-token");
        props.setProjectKey("agentbench-demo");
        props.setOrganization("demo-org");

        SonarQualityClient client = new SonarQualityClient(new ObjectMapper());
        QualityToolReport report = client.fetchIssues(props, null);

        assertFalse(report.stub());
        assertEquals(2, report.findings().size());
        assertTrue(report.notes().startsWith("sonar_api_fetch:"));
        sonar.verify(getRequestedFor(urlPathEqualTo("/api/issues/search"))
                .withQueryParam("organization", equalTo("demo-org"))
                .withHeader("Authorization", containing("Basic ")));
    }

    @Test
    void returnsStubOnHttpError() {
        sonar.stubFor(get(urlPathEqualTo("/api/issues/search"))
                .willReturn(aResponse().withStatus(401).withBody("unauthorized")));

        SonarProperties props = new SonarProperties();
        props.setEnabled(true);
        props.setBaseUrl(sonar.baseUrl());
        props.setToken("bad-token");
        props.setProjectKey("agentbench-demo");

        QualityToolReport report = new SonarQualityClient(new ObjectMapper()).fetchIssues(props, null);
        assertTrue(report.stub());
        assertEquals("sonar_api_http_401", report.notes());
    }
}
