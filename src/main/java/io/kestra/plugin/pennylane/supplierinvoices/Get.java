package io.kestra.plugin.pennylane.supplierinvoices;

import io.kestra.core.http.HttpRequest;
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

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Get a Pennylane supplier invoice",
    description = "Retrieves a single supplier invoice by its ID."
)
@Plugin(
    examples = {
        @Example(
            title = "Get a supplier invoice by ID",
            full = true,
            code = """
                id: get_pennylane_supplier_invoice
                namespace: company.finance

                tasks:
                  - id: get_invoice
                    type: io.kestra.plugin.pennylane.supplierinvoices.Get
                    apiToken: "{{ secret('PENNYLANE_API_TOKEN') }}"
                    invoiceId: 12345
                """
        )
    }
)
public class Get extends AbstractPennylaneTask implements RunnableTask<Get.Output> {

    @Schema(
        title = "Supplier invoice ID",
        description = "Unique identifier of the supplier invoice to retrieve."
    )
    @NotNull
    @PluginProperty(group = "main")
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

        return Output.builder()
            .row(invoice)
            .build();
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(title = "The retrieved supplier invoice")
        private final SupplierInvoice row;
    }
}
