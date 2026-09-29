package io.kestra.plugin.pennylane;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.common.FetchType;
import io.kestra.core.runners.RunContext;
import io.kestra.core.runners.RunContextFactory;
import io.kestra.core.serializers.FileSerde;
import io.kestra.core.utils.TestsUtils;
import io.kestra.plugin.pennylane.models.SupplierInvoice;
import io.kestra.plugin.pennylane.supplierinvoices.Get;
import io.kestra.plugin.pennylane.supplierinvoices.List;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

@KestraTest
class SupplierInvoicesTest {

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
    void testListFetchTypes() throws Exception {
        wireMockServer.stubFor(get(urlPathEqualTo("/api/external/v2/supplier_invoices"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                    {
                        "has_more": false,
                        "next_cursor": null,
                        "items": [
                            {"id": 1, "invoice_number": "INV-1", "amount": "100.0"},
                            {"id": 2, "invoice_number": "INV-2", "amount": "200.0"}
                        ]
                    }
                    """)));

        // 1. FETCH
        List fetchTask = List.builder()
            .id("test-fetch")
            .type(List.class.getName())
            .apiToken(Property.ofValue("token"))
            .baseUrl(Property.ofValue(getBaseUrl()))
            .fetchType(Property.ofValue(FetchType.FETCH))
            .build();
        RunContext rc1 = TestsUtils.mockRunContext(runContextFactory, fetchTask, Map.of());
        List.Output out1 = fetchTask.run(rc1);
        assertThat(out1.getRows(), hasSize(2));
        assertThat(out1.getCount(), is(2));
        assertThat(out1.getRow(), nullValue());
        assertThat(out1.getUri(), nullValue());

        // 2. FETCH_ONE
        List fetchOneTask = List.builder()
            .id("test-fetch-one")
            .type(List.class.getName())
            .apiToken(Property.ofValue("token"))
            .baseUrl(Property.ofValue(getBaseUrl()))
            .fetchType(Property.ofValue(FetchType.FETCH_ONE))
            .build();
        RunContext rc2 = TestsUtils.mockRunContext(runContextFactory, fetchOneTask, Map.of());
        List.Output out2 = fetchOneTask.run(rc2);
        assertThat(out2.getRow(), notNullValue());
        assertThat(out2.getRow().getId(), is(1L));
        assertThat(out2.getRows(), nullValue());
        assertThat(out2.getCount(), is(2));

        // 3. STORE
        List storeTask = List.builder()
            .id("test-store")
            .type(List.class.getName())
            .apiToken(Property.ofValue("token"))
            .baseUrl(Property.ofValue(getBaseUrl()))
            .fetchType(Property.ofValue(FetchType.STORE))
            .build();
        RunContext rc3 = TestsUtils.mockRunContext(runContextFactory, storeTask, Map.of());
        List.Output out3 = storeTask.run(rc3);
        assertThat(out3.getUri(), notNullValue());
        assertThat(out3.getCount(), is(2));

        java.util.List<SupplierInvoice> storedItems = new ArrayList<>();
        try (var is = rc3.storage().getFile(out3.getUri());
             var reader = new BufferedReader(new InputStreamReader(is))) {
            storedItems.addAll(FileSerde.readAll(reader, SupplierInvoice.class).collectList().block());
        }
        assertThat(storedItems, hasSize(2));
        assertThat(storedItems.get(0).getInvoiceNumber(), is("INV-1"));
        assertThat(storedItems.get(1).getInvoiceNumber(), is("INV-2"));

        // 4. NONE
        List noneTask = List.builder()
            .id("test-none")
            .type(List.class.getName())
            .apiToken(Property.ofValue("token"))
            .baseUrl(Property.ofValue(getBaseUrl()))
            .fetchType(Property.ofValue(FetchType.NONE))
            .build();
        RunContext rc4 = TestsUtils.mockRunContext(runContextFactory, noneTask, Map.of());
        List.Output out4 = noneTask.run(rc4);
        assertThat(out4.getCount(), is(2));
        assertThat(out4.getRows(), nullValue());
        assertThat(out4.getRow(), nullValue());
        assertThat(out4.getUri(), nullValue());
    }

    @Test
    void testGetSupplierInvoice() throws Exception {
        wireMockServer.stubFor(get(urlPathEqualTo("/api/external/v2/supplier_invoices/5001"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                    {
                        "id": 5001,
                        "invoice_number": "INV-SINGLE-5001",
                        "label": "Supplier Hosting",
                        "currency": "EUR",
                        "amount": "450.00",
                        "payment_status": "paid",
                        "public_file_url": "https://app.pennylane.com/public/invoice/pdf?encrypted_id=xyz"
                    }
                    """)));

        Get task = Get.builder()
            .id("test-get")
            .type(Get.class.getName())
            .apiToken(Property.ofValue("token"))
            .baseUrl(Property.ofValue(getBaseUrl()))
            .invoiceId(Property.ofValue(5001L))
            .build();

        RunContext runContext = TestsUtils.mockRunContext(runContextFactory, task, Map.of());
        Get.Output output = task.run(runContext);

        assertThat(output.getRow(), notNullValue());
        assertThat(output.getRow().getId(), is(5001L));
        assertThat(output.getRow().getInvoiceNumber(), is("INV-SINGLE-5001"));
        assertThat(output.getRow().getLabel(), is("Supplier Hosting"));
        assertThat(output.getRow().getPaymentStatus(), is("paid"));
        assertThat(output.getRow().getPublicFileUrl(), containsString("encrypted_id=xyz"));
    }

    @Test
    void testDownloadSupplierInvoiceDocument() throws Exception {
        wireMockServer.stubFor(get(urlPathEqualTo("/api/external/v2/supplier_invoices/5002"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                    {
                        "id": 5002,
                        "invoice_number": "INV-5002",
                        "filename": "aws-bill.pdf",
                        "public_file_url": "%s/files/aws-bill.pdf"
                    }
                    """.formatted(wireMockServer.baseUrl()))));

        wireMockServer.stubFor(get(urlPathEqualTo("/files/aws-bill.pdf"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/pdf")
                .withBody("%PDF-1.4 test document content")));

        io.kestra.plugin.pennylane.supplierinvoices.Download task = io.kestra.plugin.pennylane.supplierinvoices.Download.builder()
            .id("test-download")
            .type(io.kestra.plugin.pennylane.supplierinvoices.Download.class.getName())
            .apiToken(Property.ofValue("token"))
            .baseUrl(Property.ofValue(getBaseUrl()))
            .invoiceId(Property.ofValue(5002L))
            .build();

        RunContext runContext = TestsUtils.mockRunContext(runContextFactory, task, Map.of());
        io.kestra.plugin.pennylane.supplierinvoices.Download.Output output = task.run(runContext);

        assertThat(output.getUri(), notNullValue());
        assertThat(output.getFilename(), is("aws-bill.pdf"));
        assertThat(output.getInvoiceId(), is(5002L));

        try (var is = runContext.storage().getFile(output.getUri())) {
            String content = new String(is.readAllBytes());
            assertThat(content, containsString("%PDF-1.4"));
        }
    }

    @Test
    void testMatchedTransactions() throws Exception {
        wireMockServer.stubFor(get(urlPathEqualTo("/api/external/v2/supplier_invoices/5001/matched_transactions"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                    {
                        "has_more": false,
                        "next_cursor": null,
                        "items": [
                            {
                                "id": 7001,
                                "amount": "-450.00",
                                "currency": "EUR",
                                "label": "AWS Payment"
                            }
                        ]
                    }
                    """)));

        io.kestra.plugin.pennylane.supplierinvoices.MatchedTransactions task = io.kestra.plugin.pennylane.supplierinvoices.MatchedTransactions.builder()
            .id("test-matched-txns")
            .type(io.kestra.plugin.pennylane.supplierinvoices.MatchedTransactions.class.getName())
            .apiToken(Property.ofValue("token"))
            .baseUrl(Property.ofValue(getBaseUrl()))
            .invoiceId(Property.ofValue(5001L))
            .build();

        RunContext runContext = TestsUtils.mockRunContext(runContextFactory, task, Map.of());
        io.kestra.plugin.pennylane.supplierinvoices.MatchedTransactions.Output output = task.run(runContext);

        assertThat(output.getRows(), hasSize(1));
        assertThat(output.getCount(), is(1));
        assertThat(output.getRows().get(0).getId(), is(7001L));
        assertThat(output.getRows().get(0).getLabel(), is("AWS Payment"));
    }
}
