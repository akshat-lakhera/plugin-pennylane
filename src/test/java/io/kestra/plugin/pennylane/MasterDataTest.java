package io.kestra.plugin.pennylane;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContext;
import io.kestra.core.runners.RunContextFactory;
import io.kestra.core.utils.TestsUtils;
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
class MasterDataTest {

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
    void testSuppliers() throws Exception {
        wireMockServer.stubFor(get(urlPathEqualTo("/api/external/v2/suppliers"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                    {
                        "has_more": false,
                        "next_cursor": null,
                        "items": [
                            {"id": 11, "name": "AWS EMEA", "city": "Luxembourg"}
                        ]
                    }
                    """)));

        wireMockServer.stubFor(get(urlPathEqualTo("/api/external/v2/suppliers/11"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                    {"id": 11, "name": "AWS EMEA", "city": "Luxembourg", "vat_number": "LU12345678"}
                    """)));

        var listTask = io.kestra.plugin.pennylane.masterdata.suppliers.List.builder()
            .id("test-sup-list")
            .type(io.kestra.plugin.pennylane.masterdata.suppliers.List.class.getName())
            .apiToken(Property.ofValue("token"))
            .baseUrl(Property.ofValue(getBaseUrl()))
            .build();

        RunContext rcList = TestsUtils.mockRunContext(runContextFactory, listTask, Map.of());
        var listOut = listTask.run(rcList);
        assertThat(listOut.getCount(), is(1));
        assertThat(listOut.getRows().get(0).getName(), is("AWS EMEA"));

        var getTask = io.kestra.plugin.pennylane.masterdata.suppliers.Get.builder()
            .id("test-sup-get")
            .type(io.kestra.plugin.pennylane.masterdata.suppliers.Get.class.getName())
            .apiToken(Property.ofValue("token"))
            .baseUrl(Property.ofValue(getBaseUrl()))
            .supplierId(Property.ofValue(11L))
            .build();

        RunContext rcGet = TestsUtils.mockRunContext(runContextFactory, getTask, Map.of());
        var getOut = getTask.run(rcGet);
        assertThat(getOut.getRow(), notNullValue());
        assertThat(getOut.getRow().getName(), is("AWS EMEA"));
        assertThat(getOut.getRow().getVatNumber(), is("LU12345678"));
    }

    @Test
    void testCustomers() throws Exception {
        wireMockServer.stubFor(get(urlPathEqualTo("/api/external/v2/customers"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                    {
                        "has_more": false,
                        "next_cursor": null,
                        "items": [
                            {"id": 22, "name": "Acme Global", "customer_type": "company"}
                        ]
                    }
                    """)));

        wireMockServer.stubFor(get(urlPathEqualTo("/api/external/v2/customers/22"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                    {"id": 22, "name": "Acme Global", "customer_type": "company", "city": "Paris"}
                    """)));

        var listTask = io.kestra.plugin.pennylane.masterdata.customers.List.builder()
            .id("test-cust-list")
            .type(io.kestra.plugin.pennylane.masterdata.customers.List.class.getName())
            .apiToken(Property.ofValue("token"))
            .baseUrl(Property.ofValue(getBaseUrl()))
            .build();

        RunContext rcList = TestsUtils.mockRunContext(runContextFactory, listTask, Map.of());
        var listOut = listTask.run(rcList);
        assertThat(listOut.getCount(), is(1));
        assertThat(listOut.getRows().get(0).getName(), is("Acme Global"));

        var getTask = io.kestra.plugin.pennylane.masterdata.customers.Get.builder()
            .id("test-cust-get")
            .type(io.kestra.plugin.pennylane.masterdata.customers.Get.class.getName())
            .apiToken(Property.ofValue("token"))
            .baseUrl(Property.ofValue(getBaseUrl()))
            .customerId(Property.ofValue(22L))
            .build();

        RunContext rcGet = TestsUtils.mockRunContext(runContextFactory, getTask, Map.of());
        var getOut = getTask.run(rcGet);
        assertThat(getOut.getRow(), notNullValue());
        assertThat(getOut.getRow().getName(), is("Acme Global"));
        assertThat(getOut.getRow().getCity(), is("Paris"));
    }

    @Test
    void testBankAccounts() throws Exception {
        wireMockServer.stubFor(get(urlPathEqualTo("/api/external/v2/bank_accounts"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                    {
                        "has_more": false,
                        "next_cursor": null,
                        "items": [
                            {"id": 33, "name": "Main Checking", "iban": "FR7630006000011234567890189"}
                        ]
                    }
                    """)));

        wireMockServer.stubFor(get(urlPathEqualTo("/api/external/v2/bank_accounts/33"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                    {"id": 33, "name": "Main Checking", "iban": "FR7630006000011234567890189", "current_balance": "85400.00"}
                    """)));

        var listTask = io.kestra.plugin.pennylane.masterdata.bankaccounts.List.builder()
            .id("test-bank-list")
            .type(io.kestra.plugin.pennylane.masterdata.bankaccounts.List.class.getName())
            .apiToken(Property.ofValue("token"))
            .baseUrl(Property.ofValue(getBaseUrl()))
            .build();

        RunContext rcList = TestsUtils.mockRunContext(runContextFactory, listTask, Map.of());
        var listOut = listTask.run(rcList);
        assertThat(listOut.getCount(), is(1));
        assertThat(listOut.getRows().get(0).getName(), is("Main Checking"));

        var getTask = io.kestra.plugin.pennylane.masterdata.bankaccounts.Get.builder()
            .id("test-bank-get")
            .type(io.kestra.plugin.pennylane.masterdata.bankaccounts.Get.class.getName())
            .apiToken(Property.ofValue("token"))
            .baseUrl(Property.ofValue(getBaseUrl()))
            .bankAccountId(Property.ofValue(33L))
            .build();

        RunContext rcGet = TestsUtils.mockRunContext(runContextFactory, getTask, Map.of());
        var getOut = getTask.run(rcGet);
        assertThat(getOut.getRow(), notNullValue());
        assertThat(getOut.getRow().getName(), is("Main Checking"));
        assertThat(getOut.getRow().getCurrentBalance(), is("85400.00"));
    }
}
