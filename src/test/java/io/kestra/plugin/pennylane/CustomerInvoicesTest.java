package io.kestra.plugin.pennylane;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContext;
import io.kestra.core.runners.RunContextFactory;
import io.kestra.core.utils.TestsUtils;
import io.kestra.plugin.pennylane.customerinvoices.Get;
import io.kestra.plugin.pennylane.customerinvoices.List;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

@KestraTest
class CustomerInvoicesTest {

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
    void testListCustomerInvoices() throws Exception {
        wireMockServer.stubFor(get(urlPathEqualTo("/api/external/v2/customer_invoices"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                    {
                        "has_more": false,
                        "next_cursor": null,
                        "items": [
                            {"id": 401, "invoice_number": "FC-2026-001", "draft": false, "amount": "1200.00"},
                            {"id": 402, "invoice_number": "FC-2026-002", "draft": true, "amount": "850.00"}
                        ]
                    }
                    """)));

        List task = List.builder()
            .id("test-customer-list")
            .type(List.class.getName())
            .apiToken(Property.ofValue("token"))
            .baseUrl(Property.ofValue(getBaseUrl()))
            .customerId(Property.ofValue(100L))
            .build();

        RunContext runContext = TestsUtils.mockRunContext(runContextFactory, task, Map.of());
        List.Output output = task.run(runContext);

        assertThat(output.getCount(), is(2));
        assertThat(output.getRows(), hasSize(2));
        assertThat(output.getRows().get(0).getId(), is(401L));
        assertThat(output.getRows().get(0).getInvoiceNumber(), is("FC-2026-001"));
        assertThat(output.getRows().get(0).getDraft(), is(false));
    }

    @Test
    void testGetCustomerInvoice() throws Exception {
        wireMockServer.stubFor(get(urlPathEqualTo("/api/external/v2/customer_invoices/401"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                    {
                        "id": 401,
                        "invoice_number": "FC-2026-001",
                        "label": "Quarterly Consulting",
                        "paid": true,
                        "amount": "1200.00"
                    }
                    """)));

        Get task = Get.builder()
            .id("test-customer-get")
            .type(Get.class.getName())
            .apiToken(Property.ofValue("token"))
            .baseUrl(Property.ofValue(getBaseUrl()))
            .invoiceId(Property.ofValue(401L))
            .build();

        RunContext runContext = TestsUtils.mockRunContext(runContextFactory, task, Map.of());
        Get.Output output = task.run(runContext);

        assertThat(output.getRow(), notNullValue());
        assertThat(output.getRow().getId(), is(401L));
        assertThat(output.getRow().getInvoiceNumber(), is("FC-2026-001"));
        assertThat(output.getRow().getPaid(), is(true));
    }
}
