package io.kestra.plugin.pennylane.supplierinvoices;

import io.kestra.core.http.HttpRequest;
import io.kestra.core.http.client.HttpClient;
import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.pennylane.AbstractPennylaneTask;
import io.kestra.plugin.pennylane.models.SupplierInvoice;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Download Pennylane supplier invoice document",
    description = "Retrieves a supplier invoice from Pennylane and downloads the attached vendor PDF or e-invoice file directly into Kestra internal storage."
)
@Plugin(
    examples = {
        @Example(
            title = "Download supplier invoice PDF to internal storage",
            full = true,
            code = """
                id: download_invoice
                namespace: company.finance

                tasks:
                  - id: download
                    type: io.kestra.plugin.pennylane.supplierinvoices.Download
                    apiToken: "{{ secret('PENNYLANE_API_TOKEN') }}"
                    invoiceId: 12345
                """
        )
    }
)
public class Download extends AbstractPennylaneTask implements RunnableTask<Download.Output> {

    @Schema(
        title = "Supplier invoice identifier",
        description = "Unique ID of the supplier invoice whose attached document to download."
    )
    @NotNull
    @PluginProperty(group = "processing")
    private Property<Long> invoiceId;

    @Override
    public Output run(RunContext runContext) throws Exception {
        Long rInvoiceId = runContext.render(this.invoiceId).as(Long.class).orElseThrow(
            () -> new IllegalArgumentException("invoiceId is required")
        );

        String rBaseUrl = renderBaseUrl(runContext);
        String url = join(rBaseUrl, "supplier_invoices/" + rInvoiceId);

        var requestBuilder = HttpRequest.builder()
            .uri(URI.create(url))
            .method("GET");

        SupplierInvoice invoice = request(runContext, requestBuilder, SupplierInvoice.class).getBody();

        if (invoice == null) {
            throw new IllegalArgumentException("Supplier invoice " + rInvoiceId + " was not found");
        }

        String downloadUrl = invoice.getPublicFileUrl();
        if (downloadUrl == null || downloadUrl.isBlank()) {
            downloadUrl = invoice.getSourceFileUrl();
        }

        if (downloadUrl == null || downloadUrl.isBlank()) {
            throw new IllegalStateException("Supplier invoice " + rInvoiceId + " has no attached PDF or source document file");
        }

        String filename = invoice.getFilename();
        if (filename == null || filename.isBlank()) {
            filename = "supplier-invoice-" + rInvoiceId + ".pdf";
        }

        var tempFile = runContext.workingDir().createTempFile(".pdf").toFile();

        var downloadRequest = HttpRequest.builder()
            .uri(URI.create(downloadUrl))
            .method("GET")
            .build();

        try (var client = new HttpClient(runContext, this.options != null ? this.options : io.kestra.core.http.client.configurations.HttpConfiguration.builder().build())) {
            client.request(downloadRequest, response -> {
                try (var is = response.getBody()) {
                    Files.copy(is, tempFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                } catch (Exception e) {
                    throw new RuntimeException("Failed to stream downloaded invoice document", e);
                }
            });
        }

        URI storedUri = runContext.storage().putFile(tempFile);

        return Output.builder()
            .uri(storedUri)
            .filename(filename)
            .invoiceId(rInvoiceId)
            .build();
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(title = "URI of the downloaded invoice document in Kestra internal storage")
        private final URI uri;

        @Schema(title = "Original filename of the downloaded invoice document")
        private final String filename;

        @Schema(title = "Identifier of the downloaded supplier invoice")
        private final Long invoiceId;
    }
}
