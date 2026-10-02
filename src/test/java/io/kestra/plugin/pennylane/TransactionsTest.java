package io.kestra.plugin.pennylane;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContext;
import io.kestra.core.runners.RunContextFactory;
import io.kestra.core.utils.IdUtils;
import io.kestra.core.utils.TestsUtils;
import io.kestra.plugin.pennylane.transactions.Get;
import io.kestra.plugin.pennylane.transactions.List;
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
class TransactionsTest {

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
    void testListTransactions() throws Exception {
        wireMockServer.stubFor(get(urlPathEqualTo("/api/external/v2/transactions"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                    {
                        "has_more": false,
                        "next_cursor": null,
                        "items": [
                            {"id": 801, "label": "Stripe payout", "amount": "4500.00", "currency": "EUR"},
                            {"id": 802, "label": "Office supplies", "amount": "-120.50", "currency": "EUR"}
                        ]
                    }
                    """)));

        List task = List.builder()
            .id("test-tx-list-" + IdUtils.create())
            .type(List.class.getName())
            .apiToken(Property.ofValue("token"))
            .baseUrl(Property.ofValue(getBaseUrl()))
            .bankAccountId(Property.ofValue(99L))
            .build();

        RunContext runContext = TestsUtils.mockRunContext(runContextFactory, task, Map.of());
        List.Output output = task.run(runContext);

        assertThat(output.getCount(), is(2));
        assertThat(output.getRows(), hasSize(2));
        assertThat(output.getRows().get(0).getId(), is(801L));
        assertThat(output.getRows().get(0).getLabel(), is("Stripe payout"));
        assertThat(output.getRows().get(1).getId(), is(802L));
        assertThat(output.getRows().get(1).getLabel(), is("Office supplies"));
    }

    @Test
    void testGetTransaction() throws Exception {
        wireMockServer.stubFor(get(urlPathEqualTo("/api/external/v2/transactions/801"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                    {
                        "id": 801,
                        "label": "Stripe payout",
                        "amount": "4500.00",
                        "currency": "EUR",
                        "date": "2026-02-15"
                    }
                    """)));

        Get task = Get.builder()
            .id("test-tx-get-" + IdUtils.create())
            .type(Get.class.getName())
            .apiToken(Property.ofValue("token"))
            .baseUrl(Property.ofValue(getBaseUrl()))
            .transactionId(Property.ofValue(801L))
            .build();

        RunContext runContext = TestsUtils.mockRunContext(runContextFactory, task, Map.of());
        Get.Output output = task.run(runContext);

        assertThat(output.getRow(), notNullValue());
        assertThat(output.getRow().getId(), is(801L));
        assertThat(output.getRow().getLabel(), is("Stripe payout"));
        assertThat(output.getRow().getDate(), is("2026-02-15"));
    }
}
