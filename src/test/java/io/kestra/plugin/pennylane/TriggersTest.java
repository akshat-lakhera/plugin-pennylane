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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

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
            .id("flow-" + UUID.randomUUID())
            .namespace("io.kestra.plugin.pennylane.it")
            .tenantId("main")
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

    private TriggerContext createTriggerContext(Flow flow, String triggerId) {
        return TriggerContext.builder()
            .namespace(flow.getNamespace())
            .flowId(flow.getId())
            .triggerId(triggerId)
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
                                "id": 101,
                                "operation": "insert",
                                "processed_at": "2024-01-02T11:55:00Z",
                                "updated_at": "2024-01-02T11:55:00Z",
                                "created_at": "2024-01-02T11:55:00Z"
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
            .id("sup-trigger-" + UUID.randomUUID())
            .type(SupplierInvoiceTrigger.class.getName())
            .apiToken(Property.ofValue("token"))
            .baseUrl(Property.ofValue(getBaseUrl()))
            .interval(Duration.ofMinutes(15))
            .build();

        Flow flow = createFlow();
        TriggerContext triggerContext = createTriggerContext(flow, trigger.getId());
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
                                "id": 201,
                                "operation": "update",
                                "processed_at": "2024-01-02T11:58:00Z"
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
            .id("cust-paid-trigger-" + UUID.randomUUID())
            .type(CustomerInvoicePaidTrigger.class.getName())
            .apiToken(Property.ofValue("token"))
            .baseUrl(Property.ofValue(getBaseUrl()))
            .interval(Duration.ofMinutes(15))
            .build();

        Flow flow = createFlow();
        TriggerContext triggerContext = createTriggerContext(flow, trigger.getId());
        ConditionContext conditionContext = createConditionContext(flow, trigger, triggerContext);

        Optional<Execution> executionOpt = trigger.evaluate(conditionContext, triggerContext);

        assertThat(executionOpt.isPresent(), is(true));
        Execution execution = executionOpt.get();
        assertThat(execution.getTrigger(), notNullValue());
        assertThat(execution.getTrigger().getVariables(), notNullValue());
        assertThat(execution.getTrigger().getVariables().get("invoice"), notNullValue());
        assertThat(execution.getTrigger().getVariables().get("changeCount"), is(1));
    }

    @Test
    void testSupplierInvoiceTriggerHandlesDeletedActionGracefully() throws Exception {
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
                                "id": 999,
                                "operation": "delete",
                                "processed_at": "2024-01-02T11:50:00Z"
                            },
                            {
                                "id": 102,
                                "operation": "insert",
                                "processed_at": "2024-01-02T11:55:00Z"
                            }
                        ]
                    }
                    """)));

        wireMockServer.stubFor(get(urlPathEqualTo("/api/external/v2/supplier_invoices/102"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                    {
                        "id": 102,
                        "invoice_number": "SUP-102",
                        "supplier": {"id": 12, "name": "Google Cloud"}
                    }
                    """)));

        var trigger = SupplierInvoiceTrigger.builder()
            .id("sup-trigger-deleted-" + UUID.randomUUID())
            .type(SupplierInvoiceTrigger.class.getName())
            .apiToken(Property.ofValue("token"))
            .baseUrl(Property.ofValue(getBaseUrl()))
            .interval(Duration.ofMinutes(15))
            .build();

        Flow flow = createFlow();
        TriggerContext triggerContext = createTriggerContext(flow, trigger.getId());
        ConditionContext conditionContext = createConditionContext(flow, trigger, triggerContext);

        Optional<Execution> executionOpt = trigger.evaluate(conditionContext, triggerContext);

        assertThat(executionOpt.isPresent(), is(true));
        Execution execution = executionOpt.get();
        assertThat(execution.getTrigger(), notNullValue());
        assertThat(execution.getTrigger().getVariables().get("changeCount"), is(2));
        var invoice = (io.kestra.plugin.pennylane.models.SupplierInvoice) execution.getTrigger().getVariables().get("invoice");
        assertThat(invoice, notNullValue());
        assertThat(invoice.getId(), is(102L));
    }

    @Test
    void testTransactionTriggerFires() throws Exception {
        wireMockServer.stubFor(get(urlPathEqualTo("/api/external/v2/changelogs/transactions"))
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
                                "operation": "insert",
                                "processed_at": "2024-01-02T11:58:00Z"
                            }
                        ]
                    }
                    """)));

        wireMockServer.stubFor(get(urlPathEqualTo("/api/external/v2/transactions"))
            .withQueryParam("filter", containing("\"field\":\"id\""))
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
                                "categorized": false,
                                "bank_account": {"id": 42, "url": "https://app.pennylane.com/api/external/v2/bank_accounts/42"},
                                "categories": []
                            }
                        ]
                    }
                    """)));

        var trigger = TransactionTrigger.builder()
            .id("txn-trigger-" + UUID.randomUUID())
            .type(TransactionTrigger.class.getName())
            .apiToken(Property.ofValue("token"))
            .baseUrl(Property.ofValue(getBaseUrl()))
            .interval(Duration.ofMinutes(15))
            .categorized(Property.ofValue(false))
            .build();

        Flow flow = createFlow();
        TriggerContext triggerContext = createTriggerContext(flow, trigger.getId());
        ConditionContext conditionContext = createConditionContext(flow, trigger, triggerContext);

        Optional<Execution> executionOpt = trigger.evaluate(conditionContext, triggerContext);

        assertThat(executionOpt.isPresent(), is(true));
        Execution execution = executionOpt.get();
        assertThat(execution.getTrigger(), notNullValue());
        assertThat(execution.getTrigger().getVariables(), notNullValue());
        assertThat(execution.getTrigger().getVariables().get("transactionCount"), is(1));
        assertThat(execution.getTrigger().getVariables().get("transaction"), notNullValue());
        assertThat(execution.getTrigger().getVariables().get("transactions"), notNullValue());

        wireMockServer.verify(getRequestedFor(urlPathEqualTo("/api/external/v2/changelogs/transactions")));
        wireMockServer.verify(getRequestedFor(urlPathEqualTo("/api/external/v2/transactions"))
            .withQueryParam("filter", containing("\"field\":\"id\""))
            .withQueryParam("filter", notContaining("updated_at"))
            .withQueryParam("filter", notContaining("categorized")));
    }

    @Test
    void testTransactionTriggerOrdersByMostRecentEvent() throws Exception {
        wireMockServer.stubFor(get(urlPathEqualTo("/api/external/v2/changelogs/transactions"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                    {
                        "has_more": false,
                        "next_cursor": null,
                        "items": [
                            {"id": 401, "operation": "insert", "processed_at": "2024-01-02T11:50:00Z"},
                            {"id": 402, "operation": "insert", "processed_at": "2024-01-02T11:52:00Z"},
                            {"id": 401, "operation": "update", "processed_at": "2024-01-02T11:55:00Z"}
                        ]
                    }
                    """)));
        wireMockServer.stubFor(get(urlPathEqualTo("/api/external/v2/transactions"))
            .withQueryParam("filter", containing("\"field\":\"id\""))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                    {
                        "has_more": false,
                        "next_cursor": null,
                        "items": [
                            {"id": 401, "amount": "-1.00", "currency": "EUR", "label": "A", "categories": []},
                            {"id": 402, "amount": "-2.00", "currency": "EUR", "label": "B", "categories": []}
                        ]
                    }
                    """)));

        var trigger = TransactionTrigger.builder()
            .id("txn-trigger-order-" + UUID.randomUUID())
            .type(TransactionTrigger.class.getName())
            .apiToken(Property.ofValue("token"))
            .baseUrl(Property.ofValue(getBaseUrl()))
            .interval(Duration.ofMinutes(15))
            .build();

        Flow flow = createFlow();
        TriggerContext triggerContext = createTriggerContext(flow, trigger.getId());
        ConditionContext conditionContext = createConditionContext(flow, trigger, triggerContext);

        Optional<Execution> executionOpt = trigger.evaluate(conditionContext, triggerContext);

        assertThat(executionOpt.isPresent(), is(true));
        var variables = executionOpt.get().getTrigger().getVariables();
        var latest = (io.kestra.plugin.pennylane.models.Transaction) variables.get("transaction");
        @SuppressWarnings("unchecked")
        var all = (List<io.kestra.plugin.pennylane.models.Transaction>) variables.get("transactions");
        assertThat(latest.getId(), is(401L));
        assertThat(all.stream().map(io.kestra.plugin.pennylane.models.Transaction::getId).toList(), contains(402L, 401L));
    }

    @Test
    void testSupplierInvoiceTriggerDoesNotRefireSameWatermark() throws Exception {
        wireMockServer.stubFor(get(urlPathEqualTo("/api/external/v2/changelogs/supplier_invoices"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                    {
                        "has_more": false,
                        "items": [
                            {"id": 101, "operation": "insert", "processed_at": "2024-01-02T11:55:00Z"}
                        ]
                    }
                    """)));
        wireMockServer.stubFor(get(urlPathEqualTo("/api/external/v2/supplier_invoices/101"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"id\": 101, \"invoice_number\": \"SUP-101\"}")));

        var trigger = SupplierInvoiceTrigger.builder()
            .id("sup-trigger-once-" + UUID.randomUUID())
            .type(SupplierInvoiceTrigger.class.getName())
            .apiToken(Property.ofValue("token"))
            .baseUrl(Property.ofValue(getBaseUrl()))
            .interval(Duration.ofMinutes(15))
            .build();

        Flow flow = createFlow();
        TriggerContext triggerContext = createTriggerContext(flow, trigger.getId());
        ConditionContext conditionContext = createConditionContext(flow, trigger, triggerContext);

        assertThat(trigger.evaluate(conditionContext, triggerContext).isPresent(), is(true));
        assertThat(trigger.evaluate(conditionContext, triggerContext).isPresent(), is(false));
    }

    @Test
    void testSupplierInvoiceTriggerSkipsDeletesAndFailedFetches() throws Exception {
        wireMockServer.stubFor(get(urlPathEqualTo("/api/external/v2/changelogs/supplier_invoices"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                    {
                        "has_more": false,
                        "items": [
                            {"id": 404, "operation": "insert", "processed_at": "2024-01-02T11:50:00Z"},
                            {"id": 777, "operation": "delete", "processed_at": "2024-01-02T11:56:00Z"}
                        ]
                    }
                    """)));
        wireMockServer.stubFor(get(urlPathEqualTo("/api/external/v2/supplier_invoices/404"))
            .willReturn(aResponse().withStatus(404).withBody("{\"error\":\"missing\"}")));

        var trigger = SupplierInvoiceTrigger.builder()
            .id("sup-trigger-skip-" + UUID.randomUUID())
            .type(SupplierInvoiceTrigger.class.getName())
            .apiToken(Property.ofValue("token"))
            .baseUrl(Property.ofValue(getBaseUrl()))
            .interval(Duration.ofMinutes(15))
            .build();

        Flow flow = createFlow();
        TriggerContext triggerContext = createTriggerContext(flow, trigger.getId());
        ConditionContext conditionContext = createConditionContext(flow, trigger, triggerContext);

        assertThat(trigger.evaluate(conditionContext, triggerContext).isEmpty(), is(true));
        wireMockServer.verify(0, getRequestedFor(urlPathEqualTo("/api/external/v2/supplier_invoices/777")));
        assertThat(trigger.evaluate(conditionContext, triggerContext).isEmpty(), is(true));
    }

    @Test
    void testEmptyPollAdvancesWatermarkPastOlderEvents() throws Exception {
        wireMockServer.stubFor(get(urlPathEqualTo("/api/external/v2/changelogs/customer_invoices"))
            .inScenario("empty-then-old")
            .whenScenarioStateIs(com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED)
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"has_more\": false, \"items\": []}"))
            .willSetStateTo("after-empty"));

        wireMockServer.stubFor(get(urlPathEqualTo("/api/external/v2/changelogs/customer_invoices"))
            .inScenario("empty-then-old")
            .whenScenarioStateIs("after-empty")
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                    {
                        "has_more": false,
                        "items": [
                            {"id": 5, "operation": "update", "processed_at": "2024-01-02T11:40:00Z"}
                        ]
                    }
                    """)));

        var trigger = CustomerInvoicePaidTrigger.builder()
            .id("paid-watermark-" + UUID.randomUUID())
            .type(CustomerInvoicePaidTrigger.class.getName())
            .apiToken(Property.ofValue("token"))
            .baseUrl(Property.ofValue(getBaseUrl()))
            .interval(Duration.ofMinutes(15))
            .build();

        Flow flow = createFlow();
        TriggerContext triggerContext = createTriggerContext(flow, trigger.getId());
        ConditionContext conditionContext = createConditionContext(flow, trigger, triggerContext);

        assertThat(trigger.evaluate(conditionContext, triggerContext).isEmpty(), is(true));
        assertThat(trigger.evaluate(conditionContext, triggerContext).isEmpty(), is(true));
        wireMockServer.verify(0, getRequestedFor(urlPathEqualTo("/api/external/v2/customer_invoices/5")));
    }

    @Test
    void testCustomerPaidTriggerKeepsEveryPaidInvoice() throws Exception {
        wireMockServer.stubFor(get(urlPathEqualTo("/api/external/v2/changelogs/customer_invoices"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                    {
                        "has_more": false,
                        "items": [
                            {"id": 1, "operation": "update", "processed_at": "2024-01-02T11:50:00Z"},
                            {"id": 2, "operation": "update", "processed_at": "2024-01-02T11:58:00Z"}
                        ]
                    }
                    """)));
        wireMockServer.stubFor(get(urlPathEqualTo("/api/external/v2/customer_invoices/1"))
            .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                .withBody("{\"id\": 1, \"paid\": false}")));
        wireMockServer.stubFor(get(urlPathEqualTo("/api/external/v2/customer_invoices/2"))
            .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                .withBody("{\"id\": 2, \"paid\": true}")));

        var trigger = CustomerInvoicePaidTrigger.builder()
            .id("paid-both-" + UUID.randomUUID())
            .type(CustomerInvoicePaidTrigger.class.getName())
            .apiToken(Property.ofValue("token"))
            .baseUrl(Property.ofValue(getBaseUrl()))
            .interval(Duration.ofMinutes(15))
            .build();

        Flow flow = createFlow();
        TriggerContext triggerContext = createTriggerContext(flow, trigger.getId());
        ConditionContext conditionContext = createConditionContext(flow, trigger, triggerContext);

        Optional<Execution> execution = trigger.evaluate(conditionContext, triggerContext);
        assertThat(execution.isPresent(), is(true));
        var invoice = (io.kestra.plugin.pennylane.models.CustomerInvoice) execution.get().getTrigger().getVariables().get("invoice");
        assertThat(invoice.getId(), is(2L));
        assertThat(execution.get().getTrigger().getVariables().get("changeCount"), is(2));
    }

    @Test
    void testSupplierInvoiceTriggerDeduplicatesEventsPerId() throws Exception {
        wireMockServer.stubFor(get(urlPathEqualTo("/api/external/v2/changelogs/supplier_invoices"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                    {
                        "has_more": false,
                        "items": [
                            {"id": 101, "operation": "insert", "processed_at": "2024-01-02T11:50:00Z"},
                            {"id": 101, "operation": "update", "processed_at": "2024-01-02T11:55:00Z"}
                        ]
                    }
                    """)));
        wireMockServer.stubFor(get(urlPathEqualTo("/api/external/v2/supplier_invoices/101"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"id\": 101, \"invoice_number\": \"SUP-101\"}")));

        var trigger = SupplierInvoiceTrigger.builder()
            .id("sup-trigger-dedup-" + UUID.randomUUID())
            .type(SupplierInvoiceTrigger.class.getName())
            .apiToken(Property.ofValue("token"))
            .baseUrl(Property.ofValue(getBaseUrl()))
            .interval(Duration.ofMinutes(15))
            .build();

        Flow flow = createFlow();
        TriggerContext triggerContext = createTriggerContext(flow, trigger.getId());
        ConditionContext conditionContext = createConditionContext(flow, trigger, triggerContext);

        Optional<Execution> executionOpt = trigger.evaluate(conditionContext, triggerContext);
        assertThat(executionOpt.isPresent(), is(true));
        // Verify invoice 101 was fetched only ONCE despite 2 changelog events
        wireMockServer.verify(1, getRequestedFor(urlPathEqualTo("/api/external/v2/supplier_invoices/101")));
    }

    @Test
    void testTriggerRecoversFrom422ExpiredStartDate() throws Exception {
        wireMockServer.stubFor(get(urlPathEqualTo("/api/external/v2/changelogs/supplier_invoices"))
            .withQueryParam("start_date", equalTo("2020-01-01T00:00:00Z"))
            .willReturn(aResponse()
                .withStatus(422)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"error\": \"start_date is older than the retention window\"}")));

        wireMockServer.stubFor(get(urlPathEqualTo("/api/external/v2/changelogs/supplier_invoices"))
            .withQueryParam("start_date", not(equalTo("2020-01-01T00:00:00Z")))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                    {
                        "has_more": false,
                        "items": [
                            {"id": 888, "operation": "insert", "processed_at": "2024-01-02T11:59:00Z"}
                        ]
                    }
                    """)));

        wireMockServer.stubFor(get(urlPathEqualTo("/api/external/v2/supplier_invoices/888"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"id\": 888, \"invoice_number\": \"SUP-888\"}")));

        var trigger = SupplierInvoiceTrigger.builder()
            .id("sup-trigger-422-" + UUID.randomUUID())
            .type(SupplierInvoiceTrigger.class.getName())
            .apiToken(Property.ofValue("token"))
            .baseUrl(Property.ofValue(getBaseUrl()))
            .interval(Duration.ofMinutes(15))
            .build();

        Flow flow = createFlow();
        TriggerContext triggerContext = createTriggerContext(flow, trigger.getId());
        ConditionContext conditionContext = createConditionContext(flow, trigger, triggerContext);

        // Preload an ancient watermark older than retention window
        String watermarkKey = PennylaneWatermark.key(flow.getId(), trigger.getId());
        PennylaneWatermark.save(
            conditionContext.getRunContext(),
            flow.getNamespace(),
            watermarkKey,
            new PennylaneWatermark.State("2020-01-01T00:00:00Z", List.of())
        );

        Optional<Execution> executionOpt = trigger.evaluate(conditionContext, triggerContext);
        assertThat(executionOpt.isPresent(), is(true));
        var invoice = (io.kestra.plugin.pennylane.models.SupplierInvoice) executionOpt.get().getTrigger().getVariables().get("invoice");
        assertThat(invoice.getId(), is(888L));
    }
}
