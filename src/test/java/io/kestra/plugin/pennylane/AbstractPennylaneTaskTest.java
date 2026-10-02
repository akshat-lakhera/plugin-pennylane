package io.kestra.plugin.pennylane;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.kestra.core.http.client.HttpClientResponseException;
import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContext;
import io.kestra.core.runners.RunContextFactory;
import io.kestra.core.utils.IdUtils;
import jakarta.validation.ConstraintViolationException;
import io.kestra.core.utils.TestsUtils;
import io.kestra.plugin.pennylane.supplierinvoices.List;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertThrows;

@KestraTest
class AbstractPennylaneTaskTest {

    private static WireMockServer wireMockServer;

    @Inject
    private RunContextFactory runContextFactory;

    @BeforeAll
    static void startWireMock() {
        wireMockServer = new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());
        wireMockServer.start();
    }

    @AfterAll
    static void stopWireMock() {
        if (wireMockServer != null) {
            wireMockServer.stop();
        }
    }

    @BeforeEach
    void resetWireMock() {
        wireMockServer.resetAll();
    }

    private String getBaseUrl() {
        return wireMockServer.baseUrl() + "/api/external/v2";
    }

    @Test
    void testAuthenticationAndPagination() throws Exception {
        wireMockServer.stubFor(get(urlPathEqualTo("/api/external/v2/supplier_invoices"))
            .withHeader("Authorization", equalTo("Bearer test-token-123"))
            .withQueryParam("cursor", absent())
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                    {
                        "has_more": true,
                        "next_cursor": "cur_page_2",
                        "items": [
                            {"id": 101, "invoice_number": "INV-001", "amount": "150.00"}
                        ]
                    }
                    """)));

        wireMockServer.stubFor(get(urlPathEqualTo("/api/external/v2/supplier_invoices"))
            .withHeader("Authorization", equalTo("Bearer test-token-123"))
            .withQueryParam("cursor", equalTo("cur_page_2"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                    {
                        "has_more": false,
                        "next_cursor": null,
                        "items": [
                            {"id": 102, "invoice_number": "INV-002", "amount": "300.50"}
                        ]
                    }
                    """)));

        List task = List.builder()
            .id("test-auth-pagination-" + IdUtils.create())
            .type(List.class.getName())
            .apiToken(Property.ofValue("test-token-123"))
            .baseUrl(Property.ofValue(getBaseUrl()))
            .build();

        RunContext runContext = TestsUtils.mockRunContext(runContextFactory, task, Map.of());
        List.Output output = task.run(runContext);

        assertThat(output.getCount(), is(2));
        assertThat(output.getRows(), hasSize(2));
        assertThat(output.getRows().get(0).getId(), is(101L));
        assertThat(output.getRows().get(0).getInvoiceNumber(), is("INV-001"));
        assertThat(output.getRows().get(1).getId(), is(102L));
        assertThat(output.getRows().get(1).getInvoiceNumber(), is("INV-002"));
    }

    @Test
    void testUnauthorizedError() {
        wireMockServer.stubFor(get(urlPathEqualTo("/api/external/v2/supplier_invoices"))
            .willReturn(aResponse()
                .withStatus(401)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"error\": \"Unauthorized\"}")));

        List task = List.builder()
            .id("test-401-" + IdUtils.create())
            .type(List.class.getName())
            .apiToken(Property.ofValue("invalid-token"))
            .baseUrl(Property.ofValue(getBaseUrl()))
            .build();

        RunContext runContext = TestsUtils.mockRunContext(runContextFactory, task, Map.of());

        HttpClientResponseException ex = assertThrows(HttpClientResponseException.class, () -> task.run(runContext));
        assertThat(ex.getMessage(), containsString("invalid or missing API token"));
    }

    @Test
    void testNotFoundError() {
        wireMockServer.stubFor(get(urlPathEqualTo("/api/external/v2/supplier_invoices"))
            .willReturn(aResponse()
                .withStatus(404)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"error\": \"Not Found\"}")));

        List task = List.builder()
            .id("test-404-" + IdUtils.create())
            .type(List.class.getName())
            .apiToken(Property.ofValue("valid-token"))
            .baseUrl(Property.ofValue(getBaseUrl()))
            .build();

        RunContext runContext = TestsUtils.mockRunContext(runContextFactory, task, Map.of());

        HttpClientResponseException ex = assertThrows(HttpClientResponseException.class, () -> task.run(runContext));
        assertThat(ex.getMessage(), containsString("resource not found"));
    }

    @Test
    void testRateLimitBackoffRetry() throws Exception {
        wireMockServer.stubFor(get(urlPathEqualTo("/api/external/v2/supplier_invoices"))
            .inScenario("RateLimit")
            .whenScenarioStateIs(com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED)
            .willReturn(aResponse()
                .withStatus(429)
                .withHeader("Content-Type", "application/json")
                .withHeader("Retry-After", "1")
                .withBody("{\"error\": \"Too Many Requests\"}"))
            .willSetStateTo("Retried"));

        wireMockServer.stubFor(get(urlPathEqualTo("/api/external/v2/supplier_invoices"))
            .inScenario("RateLimit")
            .whenScenarioStateIs("Retried")
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                    {
                        "has_more": false,
                        "next_cursor": null,
                        "items": [
                            {"id": 201, "invoice_number": "INV-RATE-LIMITED", "amount": "99.00"}
                        ]
                    }
                    """)));

        List task = List.builder()
            .id("test-429-retry-" + IdUtils.create())
            .type(List.class.getName())
            .apiToken(Property.ofValue("test-token"))
            .baseUrl(Property.ofValue(getBaseUrl()))
            .build();

        RunContext runContext = TestsUtils.mockRunContext(runContextFactory, task, Map.of());
        List.Output output = task.run(runContext);

        assertThat(output.getCount(), is(1));
        assertThat(output.getRows().get(0).getInvoiceNumber(), is("INV-RATE-LIMITED"));
    }

    @Test
    void testPageSizeIsNotClamped() {
        List task = List.builder()
            .id("test-page-size-" + IdUtils.create())
            .type(List.class.getName())
            .apiToken(Property.ofValue("token"))
            .baseUrl(Property.ofValue(getBaseUrl()))
            .pageSize(Property.ofValue(500))
            .build();

        RunContext runContext = TestsUtils.mockRunContext(runContextFactory, task, Map.of());
        var ex = assertThrows(ConstraintViolationException.class, () -> task.run(runContext));
        assertThat(ex.getMessage(), containsString("pageSize"));
    }

    @Test
    void testFetchByIdReturnsNullOn404() throws Exception {
        wireMockServer.stubFor(get(urlPathEqualTo("/api/external/v2/supplier_invoices/999"))
            .willReturn(aResponse().withStatus(404).withBody("{\"error\": \"Not Found\"}")));

        List task = List.builder()
            .id("test-fetch-404-" + IdUtils.create())
            .type(List.class.getName())
            .apiToken(Property.ofValue("token"))
            .baseUrl(Property.ofValue(getBaseUrl()))
            .build();

        RunContext runContext = TestsUtils.mockRunContext(runContextFactory, task, Map.of());
        var result = AbstractPennylaneTask.fetchById(
            runContext,
            null,
            "token",
            getBaseUrl(),
            "supplier_invoices/999",
            io.kestra.plugin.pennylane.models.SupplierInvoice.class
        );
        assertThat(result, nullValue());
    }

    @Test
    void testFetchByIdPropagates500Error() {
        wireMockServer.stubFor(get(urlPathEqualTo("/api/external/v2/supplier_invoices/500"))
            .willReturn(aResponse().withStatus(500).withBody("{\"error\": \"Internal Server Error\"}")));

        List task = List.builder()
            .id("test-fetch-500-" + IdUtils.create())
            .type(List.class.getName())
            .apiToken(Property.ofValue("token"))
            .baseUrl(Property.ofValue(getBaseUrl()))
            .build();

        RunContext runContext = TestsUtils.mockRunContext(runContextFactory, task, Map.of());
        assertThrows(HttpClientResponseException.class, () ->
            AbstractPennylaneTask.fetchById(
                runContext,
                null,
                "token",
                getBaseUrl(),
                "supplier_invoices/500",
                io.kestra.plugin.pennylane.models.SupplierInvoice.class
            )
        );
    }
}
