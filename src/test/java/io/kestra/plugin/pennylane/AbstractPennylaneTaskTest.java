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
import io.kestra.core.models.tasks.common.FetchType;
import io.kestra.plugin.pennylane.AbstractPennylaneTask.FetchResult;
import io.kestra.plugin.pennylane.AbstractPennylaneTask.PageMode;
import io.kestra.plugin.pennylane.models.SupplierInvoice;
import io.kestra.plugin.pennylane.supplierinvoices.List;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

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

    private void stubCursorPage(String cursor, String nextCursor, boolean hasMore, String itemsJson) {
        var mapping = get(urlPathEqualTo("/api/external/v2/supplier_invoices"));
        mapping = cursor == null ? mapping.withQueryParam("cursor", absent()) : mapping.withQueryParam("cursor", equalTo(cursor));
        wireMockServer.stubFor(mapping.willReturn(aResponse()
            .withStatus(200)
            .withHeader("Content-Type", "application/json")
            .withBody("{\"has_more\": " + hasMore + ", \"next_cursor\": "
                + (nextCursor == null ? "null" : "\"" + nextCursor + "\"") + ", \"items\": " + itemsJson + "}")));
    }

    private List supplierInvoiceList(String idPrefix) {
        return List.builder()
            .id(idPrefix + IdUtils.create())
            .type(List.class.getName())
            .apiToken(Property.ofValue("token"))
            .baseUrl(Property.ofValue(getBaseUrl()))
            .build();
    }

    @Test
    void cursorCycleFailsWithAlreadySeenCursor() {
        stubCursorPage(null, "B", true, "[{\"id\": 1}]");
        stubCursorPage("B", "A", true, "[{\"id\": 2}]");
        stubCursorPage("A", "B", true, "[{\"id\": 3}]");

        List task = supplierInvoiceList("test-cursor-cycle-");
        RunContext runContext = TestsUtils.mockRunContext(runContextFactory, task, Map.of());

        var ex = assertThrows(IllegalStateException.class, () -> task.run(runContext));
        assertThat(ex.getMessage(), containsString("cursor already seen (B)"));
        assertThat(ex.getMessage(), containsString("supplier_invoices"));
    }

    @Test
    void longCursorChainCompletes() throws Exception {
        int pages = 50;
        for (int i = 0; i < pages; i++) {
            String cursor = i == 0 ? null : "cur_" + i;
            boolean last = i == pages - 1;
            stubCursorPage(cursor, last ? null : "cur_" + (i + 1), !last, "[{\"id\": " + (i + 1) + "}]");
        }

        List task = supplierInvoiceList("test-long-chain-");
        RunContext runContext = TestsUtils.mockRunContext(runContextFactory, task, Map.of());
        List.Output output = task.run(runContext);

        assertThat(output.getCount(), is(pages));
        assertThat(output.getRows(), hasSize(pages));
    }

    @Test
    void consecutiveEmptyPagesWithHasMoreFail() {
        stubCursorPage(null, "c1", true, "[]");
        stubCursorPage("c1", "c2", true, "[]");
        stubCursorPage("c2", "c3", true, "[]");
        stubCursorPage("c3", "c4", true, "[]");

        List task = supplierInvoiceList("test-empty-pages-");
        RunContext runContext = TestsUtils.mockRunContext(runContextFactory, task, Map.of());

        var ex = assertThrows(IllegalStateException.class, () -> task.run(runContext));
        assertThat(ex.getMessage(), containsString("3 consecutive empty pages"));
    }

    @Test
    void offsetPagerStopsOnEmptyPageWithoutTotalPages() throws Exception {
        wireMockServer.stubFor(get(urlPathEqualTo("/api/external/v2/ledger_entries"))
            .withQueryParam("page", equalTo("1"))
            .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                .withBody("{\"items\": [{\"id\": 1}, {\"id\": 2}]}")));
        wireMockServer.stubFor(get(urlPathEqualTo("/api/external/v2/ledger_entries"))
            .withQueryParam("page", equalTo("2"))
            .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                .withBody("{\"items\": []}")));

        var task = io.kestra.plugin.pennylane.accounting.ledgerentries.List.builder()
            .id("test-offset-empty-" + IdUtils.create())
            .type(io.kestra.plugin.pennylane.accounting.ledgerentries.List.class.getName())
            .apiToken(Property.ofValue("token"))
            .baseUrl(Property.ofValue(getBaseUrl()))
            .pageSize(Property.ofValue(2))
            .build();
        RunContext runContext = TestsUtils.mockRunContext(runContextFactory, task, Map.of());
        var output = task.run(runContext);

        assertThat(output.getCount(), is(2));
    }

    @Test
    void offsetPagerStopsOnEmptyPageEvenWhenTotalPagesIsHigher() throws Exception {
        wireMockServer.stubFor(get(urlPathEqualTo("/api/external/v2/ledger_entries"))
            .withQueryParam("page", equalTo("1"))
            .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                .withBody("{\"total_pages\": 5, \"items\": [{\"id\": 1}, {\"id\": 2}]}")));
        wireMockServer.stubFor(get(urlPathEqualTo("/api/external/v2/ledger_entries"))
            .withQueryParam("page", equalTo("2"))
            .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                .withBody("{\"total_pages\": 5, \"items\": []}")));

        var task = io.kestra.plugin.pennylane.accounting.ledgerentries.List.builder()
            .id("test-offset-empty-total-" + IdUtils.create())
            .type(io.kestra.plugin.pennylane.accounting.ledgerentries.List.class.getName())
            .apiToken(Property.ofValue("token"))
            .baseUrl(Property.ofValue(getBaseUrl()))
            .pageSize(Property.ofValue(2))
            .build();
        RunContext runContext = TestsUtils.mockRunContext(runContextFactory, task, Map.of());

        assertThat(task.run(runContext).getCount(), is(2));
    }

    private List fetchOneList(String idPrefix) {
        return List.builder()
            .id(idPrefix + IdUtils.create())
            .type(List.class.getName())
            .apiToken(Property.ofValue("token"))
            .baseUrl(Property.ofValue(getBaseUrl()))
            .fetchType(Property.ofValue(FetchType.FETCH_ONE))
            .build();
    }

    @Test
    void fetchOneStopsAfterFirstPageOfCursorList() throws Exception {
        long first = ThreadLocalRandom.current().nextLong(1, 1_000_000);
        stubCursorPage(null, "next_" + IdUtils.create(), true, "[{\"id\": " + first + "}, {\"id\": " + (first + 1) + "}]");
        // Any follow-up page request would hit this catch-all stub and be counted below.
        wireMockServer.stubFor(get(urlPathEqualTo("/api/external/v2/supplier_invoices"))
            .withQueryParam("cursor", matching(".+"))
            .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                .withBody("{\"has_more\": false, \"items\": [{\"id\": 999999999}]}")));

        List task = fetchOneList("test-fetch-one-first-page-");
        List.Output output = task.run(TestsUtils.mockRunContext(runContextFactory, task, Map.of()));

        assertThat(output.getCount(), is(1));
        assertThat(output.getRow().getId(), is(first));
        assertThat(output.getRows(), nullValue());
        wireMockServer.verify(1, getRequestedFor(urlPathEqualTo("/api/external/v2/supplier_invoices")));
    }

    @Test
    void fetchOneWithNoItemsReturnsZeroAndNullRow() throws Exception {
        stubCursorPage(null, null, false, "[]");

        List task = fetchOneList("test-fetch-one-empty-");
        List.Output output = task.run(TestsUtils.mockRunContext(runContextFactory, task, Map.of()));

        assertThat(output.getCount(), is(0));
        assertThat(output.getRow(), nullValue());
    }

    @Test
    void fetchOneSkipsRecordsRejectedByPostFilterAcrossPages() throws Exception {
        long rejectedA = ThreadLocalRandom.current().nextLong(1, 1_000_000);
        long rejectedB = rejectedA + 1;
        long match = rejectedA + 2;
        long afterMatch = rejectedA + 3;
        stubCursorPage(null, "p2", true, "[{\"id\": " + rejectedA + "}]");
        stubCursorPage("p2", "p3", true, "[{\"id\": " + rejectedB + "}]");
        stubCursorPage("p3", "p4", true, "[{\"id\": " + match + "}, {\"id\": " + afterMatch + "}]");
        stubCursorPage("p4", null, false, "[{\"id\": " + (afterMatch + 1) + "}]");

        List task = fetchOneList("test-fetch-one-filter-");
        RunContext runContext = TestsUtils.mockRunContext(runContextFactory, task, Map.of());
        FetchResult<SupplierInvoice> result = task.drain(
            runContext,
            "supplier_invoices",
            Map.of(),
            SupplierInvoice.class,
            Property.ofValue(FetchType.FETCH_ONE),
            null,
            PageMode.STANDARD,
            invoice -> invoice.getId() >= match
        );

        assertThat(result.count(), is(1));
        assertThat(result.row().getId(), is(match));
        wireMockServer.verify(3, getRequestedFor(urlPathEqualTo("/api/external/v2/supplier_invoices")));
    }

    @Test
    void fetchOneOnOffsetPagerMakesSingleRequest() throws Exception {
        long first = ThreadLocalRandom.current().nextLong(1, 1_000_000);
        wireMockServer.stubFor(get(urlPathEqualTo("/api/external/v2/ledger_entries"))
            .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                .withBody("{\"total_pages\": 5, \"items\": [{\"id\": " + first + "}, {\"id\": " + (first + 1) + "}]}")));

        var task = io.kestra.plugin.pennylane.accounting.ledgerentries.List.builder()
            .id("test-fetch-one-offset-" + IdUtils.create())
            .type(io.kestra.plugin.pennylane.accounting.ledgerentries.List.class.getName())
            .apiToken(Property.ofValue("token"))
            .baseUrl(Property.ofValue(getBaseUrl()))
            .pageSize(Property.ofValue(2))
            .fetchType(Property.ofValue(FetchType.FETCH_ONE))
            .build();
        var output = task.run(TestsUtils.mockRunContext(runContextFactory, task, Map.of()));

        assertThat(output.getCount(), is(1));
        assertThat(output.getRow().getId(), is(first));
        wireMockServer.verify(1, getRequestedFor(urlPathEqualTo("/api/external/v2/ledger_entries")));
    }
}
