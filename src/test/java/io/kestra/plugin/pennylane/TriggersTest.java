package io.kestra.plugin.pennylane;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.conditions.ConditionContext;
import io.kestra.core.models.executions.Execution;
import io.kestra.core.models.flows.Flow;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.triggers.TriggerContext;
import io.kestra.core.runners.DefaultRunContext;
import io.kestra.core.runners.RunContext;
import io.kestra.core.runners.RunContextFactory;
import io.kestra.core.runners.RunContextInitializer;
import io.kestra.plugin.pennylane.customerinvoices.CustomerInvoicePaidTrigger;
import io.kestra.plugin.pennylane.supplierinvoices.SupplierInvoiceTrigger;
import io.kestra.plugin.pennylane.transactions.TransactionTrigger;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Map;
import java.util.Optional;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

@KestraTest
class TriggersTest {

    private static WireMockServer wireMockServer;

    @Inject
    private RunContextFactory runContextFactory;

    @Inject
    private RunContextInitializer runContextInitializer;

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

    private Flow createFlow() {
        return Flow.builder()
            .id("test-flow")
            .namespace("io.kestra.test")
            .revision(1)
            .build();
    }

    private ConditionContext createConditionContext(Flow flow, io.kestra.core.models.triggers.AbstractTrigger trigger, TriggerContext triggerContext) {
        DefaultRunContext runContext = (DefaultRunContext) runContextFactory.of(flow, trigger);
        runContext = runContextInitializer.forScheduler(runContext, triggerContext, trigger);
        return ConditionContext.builder()
            .flow(flow)
            .runContext(runContext)
            .build();
    }

    private TriggerContext createTriggerContext() {
        return TriggerContext.builder()
            .namespace("io.kestra.test")
            .flowId("test-flow")
            .triggerId("test-trigger")
            .date(ZonedDateTime.of(2024, 1, 2, 12, 0, 0, 0, ZoneOffset.UTC))
            .build();
    }

    @Test
    void testSupplierInvoiceTriggerFires() throws Exception {
        wireMockServer.stubFor(get(urlPathEqualTo("/api/external/v2/changelogs/supplier_invoices"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                    {
                        "has_more": false,
                        "next_cursor": null,
                        "items": [
                            {
                                "action": "created",
                                "resource_id": 101,
                                "happened_at": "2024-01-02T11:55:00Z"
                            }
                        ]
                    }
                    """)));

        wireMockServer.stubFor(get(urlPathEqualTo("/api/external/v2/supplier_invoices/101"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                    {
                        "id": 101,
                        "invoice_number": "SUP-101",
                        "supplier": {"id": 11, "name": "AWS EMEA"}
                    }
                    """)));

        var trigger = SupplierInvoiceTrigger.builder()
            .id("sup-trigger")
            .type(SupplierInvoiceTrigger.class.getName())
            .apiToken(Property.ofValue("token"))
            .baseUrl(Property.ofValue(getBaseUrl()))
            .interval(Duration.ofMinutes(15))
            .build();

        Flow flow = createFlow();
        TriggerContext triggerContext = createTriggerContext();
        ConditionContext conditionContext = createConditionContext(flow, trigger, triggerContext);

        Optional<Execution> executionOpt = trigger.evaluate(conditionContext, triggerContext);

        assertThat(executionOpt.isPresent(), is(true));
        Execution execution = executionOpt.get();
        assertThat(execution.getTrigger(), notNullValue());
        assertThat(execution.getTrigger().getVariables(), notNullValue());
        assertThat(execution.getTrigger().getVariables().get("changeCount"), is(1));
        assertThat(execution.getTrigger().getVariables().get("invoice"), notNullValue());
    }

    @Test
    void testCustomerInvoicePaidTriggerFires() throws Exception {
        wireMockServer.stubFor(get(urlPathEqualTo("/api/external/v2/changelogs/customer_invoices"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                    {
                        "has_more": false,
                        "next_cursor": null,
                        "items": [
                            {
                                "action": "updated",
                                "resource_id": 201,
                                "happened_at": "2024-01-02T11:58:00Z"
                            }
                        ]
                    }
                    """)));

        wireMockServer.stubFor(get(urlPathEqualTo("/api/external/v2/customer_invoices/201"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                    {
                        "id": 201,
                        "invoice_number": "INV-201",
                        "paid": true,
                        "customer": {"id": 22, "name": "Acme Global"}
                    }
                    """)));

        var trigger = CustomerInvoicePaidTrigger.builder()
            .id("cust-paid-trigger")
            .type(CustomerInvoicePaidTrigger.class.getName())
            .apiToken(Property.ofValue("token"))
            .baseUrl(Property.ofValue(getBaseUrl()))
            .interval(Duration.ofMinutes(15))
            .build();

        Flow flow = createFlow();
        TriggerContext triggerContext = createTriggerContext();
        ConditionContext conditionContext = createConditionContext(flow, trigger, triggerContext);

        Optional<Execution> executionOpt = trigger.evaluate(conditionContext, triggerContext);

        assertThat(executionOpt.isPresent(), is(true));
        Execution execution = executionOpt.get();
        assertThat(execution.getTrigger(), notNullValue());
        assertThat(execution.getTrigger().getVariables(), notNullValue());
        assertThat(execution.getTrigger().getVariables().get("invoice"), notNullValue());
    }

    @Test
    void testTransactionTriggerFires() throws Exception {
        wireMockServer.stubFor(get(urlPathEqualTo("/api/external/v2/transactions"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                    {
                        "has_more": false,
                        "next_cursor": null,
                        "items": [
                            {
                                "id": 301,
                                "amount": "-49.99",
                                "currency": "EUR",
                                "label": "Cloud Hosting",
                                "categorized": false
                            }
                        ]
                    }
                    """)));

        var trigger = TransactionTrigger.builder()
            .id("txn-trigger")
            .type(TransactionTrigger.class.getName())
            .apiToken(Property.ofValue("token"))
            .baseUrl(Property.ofValue(getBaseUrl()))
            .interval(Duration.ofMinutes(15))
            .categorized(Property.ofValue(false))
            .build();

        Flow flow = createFlow();
        TriggerContext triggerContext = createTriggerContext();
        ConditionContext conditionContext = createConditionContext(flow, trigger, triggerContext);

        Optional<Execution> executionOpt = trigger.evaluate(conditionContext, triggerContext);

        assertThat(executionOpt.isPresent(), is(true));
        Execution execution = executionOpt.get();
        assertThat(execution.getTrigger(), notNullValue());
        assertThat(execution.getTrigger().getVariables(), notNullValue());
        assertThat(execution.getTrigger().getVariables().get("transactionCount"), is(1));
        assertThat(execution.getTrigger().getVariables().get("transaction"), notNullValue());
    }
}
