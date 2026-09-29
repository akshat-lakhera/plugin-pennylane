package io.kestra.plugin.pennylane;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContext;
import io.kestra.core.runners.RunContextFactory;
import io.kestra.core.utils.TestsUtils;
import io.kestra.plugin.pennylane.accounting.trialbalance.Get;
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
class AccountingTest {

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
    void testTrialBalanceGet() throws Exception {
        wireMockServer.stubFor(get(urlPathEqualTo("/api/external/v2/trial_balance"))
            .withQueryParam("period_start", equalTo("2024-01-01"))
            .withQueryParam("period_end", equalTo("2024-12-31"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                    {
                        "total_debit": "150000.00",
                        "total_credit": "150000.00",
                        "ledger_accounts": [
                            {
                                "ledger_account_number": "401000",
                                "ledger_account_label": "Suppliers",
                                "debit": "50000.00",
                                "credit": "0.00",
                                "balance": "50000.00"
                            },
                            {
                                "ledger_account_number": "411000",
                                "ledger_account_label": "Customers",
                                "debit": "100000.00",
                                "credit": "150000.00",
                                "balance": "-50000.00"
                            }
                        ]
                    }
                    """)));

        var task = Get.builder()
            .id("test-trial-balance-get")
            .type(Get.class.getName())
            .apiToken(Property.ofValue("token"))
            .baseUrl(Property.ofValue(getBaseUrl()))
            .periodStart(Property.ofValue("2024-01-01"))
            .periodEnd(Property.ofValue("2024-12-31"))
            .build();

        RunContext rc = TestsUtils.mockRunContext(runContextFactory, task, Map.of());
        var output = task.run(rc);

        assertThat(output.getCount(), is(2));
        assertThat(output.getRows(), hasSize(2));
        assertThat(output.getRows().get(0).getNumber(), is("401000"));
        assertThat(output.getRows().get(0).getLabel(), is("Suppliers"));
        assertThat(output.getRows().get(1).getNumber(), is("411000"));
        assertThat(output.getRows().get(1).getLabel(), is("Customers"));
    }

    @Test
    void testLedgerAccountsList() throws Exception {
        wireMockServer.stubFor(get(urlPathEqualTo("/api/external/v2/ledger_accounts"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                    {
                        "has_more": false,
                        "next_cursor": null,
                        "items": [
                            {"id": 1001, "number": "401000", "label": "Fournisseurs", "enabled": true}
                        ]
                    }
                    """)));

        var task = io.kestra.plugin.pennylane.accounting.ledgeraccounts.List.builder()
            .id("test-ledger-accounts-list")
            .type(io.kestra.plugin.pennylane.accounting.ledgeraccounts.List.class.getName())
            .apiToken(Property.ofValue("token"))
            .baseUrl(Property.ofValue(getBaseUrl()))
            .build();

        RunContext rc = TestsUtils.mockRunContext(runContextFactory, task, Map.of());
        var output = task.run(rc);

        assertThat(output.getCount(), is(1));
        assertThat(output.getRows(), hasSize(1));
        assertThat(output.getRows().get(0).getId(), is(1001L));
        assertThat(output.getRows().get(0).getNumber(), is("401000"));
        assertThat(output.getRows().get(0).getLabel(), is("Fournisseurs"));
    }

    @Test
    void testLedgerEntryLinesList() throws Exception {
        wireMockServer.stubFor(get(urlPathEqualTo("/api/external/v2/ledger_entry_lines"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                    {
                        "has_more": false,
                        "next_cursor": null,
                        "items": [
                            {
                                "id": 2001,
                                "ledger_account_id": 1001,
                                "debit": "120.00",
                                "credit": "0.00",
                                "currency": "EUR"
                            }
                        ]
                    }
                    """)));

        var task = io.kestra.plugin.pennylane.accounting.ledgerentrylines.List.builder()
            .id("test-ledger-entry-lines-list")
            .type(io.kestra.plugin.pennylane.accounting.ledgerentrylines.List.class.getName())
            .apiToken(Property.ofValue("token"))
            .baseUrl(Property.ofValue(getBaseUrl()))
            .build();

        RunContext rc = TestsUtils.mockRunContext(runContextFactory, task, Map.of());
        var output = task.run(rc);

        assertThat(output.getCount(), is(1));
        assertThat(output.getRows(), hasSize(1));
        assertThat(output.getRows().get(0).getId(), is(2001L));
        assertThat(output.getRows().get(0).getLedgerAccountId(), is(1001L));
        assertThat(output.getRows().get(0).getCurrency(), is("EUR"));
    }
}
