package io.kestra.plugin.pennylane.supplierinvoices;

import com.fasterxml.jackson.core.type.TypeReference;
import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.models.tasks.common.FetchType;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.pennylane.AbstractPennylaneTask;
import io.kestra.plugin.pennylane.models.PennylaneFilter;
import io.kestra.plugin.pennylane.models.SupplierInvoice;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "List Pennylane supplier invoices",
    description = "Retrieves supplier invoices from Pennylane with automatic cursor-based pagination and optional filtering."
)
@Plugin(
    examples = {
        @Example(
            title = "List unpaid supplier invoices and store in internal storage",
            full = true,
            code = """
                id: pennylane_supplier_invoices
                namespace: company.finance

                tasks:
                  - id: supplier_invoices
                    type: io.kestra.plugin.pennylane.supplierinvoices.List
                    apiToken: "{{ secret('PENNYLANE_API_TOKEN') }}"
                    dateFrom: "2026-01-01"
                    paymentStatus: "not_paid"
                    fetchType: STORE
                """
        )
    }
)
public class List extends AbstractPennylaneTask implements RunnableTask<List.Output> {

    @Schema(
        title = "Invoice issue start date",
        description = "Filters supplier invoices issued on or after this date (format YYYY-MM-DD)."
    )
    @PluginProperty(group = "processing")
    private Property<String> dateFrom;

    @Schema(
        title = "Invoice issue end date",
        description = "Filters supplier invoices issued on or before this date (format YYYY-MM-DD)."
    )
    @PluginProperty(group = "processing")
    private Property<String> dateTo;

    @Schema(
        title = "Supplier identifier",
        description = "Filters supplier invoices belonging to a specific supplier ID."
    )
    @PluginProperty(group = "processing")
    private Property<Long> supplierId;

    @Schema(
        title = "Payment status",
        description = "Filters invoices by payment status, e.g. 'paid', 'not_paid', or 'partially_paid'."
    )
    @PluginProperty(group = "processing")
    private Property<String> paymentStatus;

    @Schema(
        title = "Raw Pennylane filter DSL",
        description = "Raw JSON filter string matching Pennylane filter DSL, e.g. `[{\"field\": \"invoice_number\", \"operator\": \"eq\", \"value\": \"INV-123\"}]`."
    )
    @PluginProperty(group = "processing")
    private Property<String> filter;

    @Schema(
        title = "Sort order",
        description = "Attribute to sort by, optionally prefixed with '-' for descending order. Defaults to '-id'."
    )
    @Builder.Default
    @PluginProperty(group = "processing")
    private Property<String> sort = Property.ofValue("-id");

    @Schema(
        title = "Page size",
        description = "Number of items per request page (1 to 100). Defaults to 100."
    )
    @Builder.Default
    @PluginProperty(group = "processing")
    private Property<Integer> pageSize = Property.ofValue(100);

    @Schema(
        title = "Maximum records",
        description = "Maximum total number of records to retrieve across all pages. Omit to fetch all matching records."
    )
    @PluginProperty(group = "processing")
    private Property<Integer> maxRecords;

    @Schema(
        title = "Fetch type",
        description = "Defines how results are emitted: FETCH (in-memory list), FETCH_ONE (first item), STORE (written to internal storage .ion file), or NONE (count only). Defaults to FETCH."
    )
    @Builder.Default
    @PluginProperty(group = "processing")
    private Property<FetchType> fetchType = Property.ofValue(FetchType.FETCH);

    @Override
    public Output run(RunContext runContext) throws Exception {
        Map<String, String> queryParams = new LinkedHashMap<>();

        int limit = runContext.render(this.pageSize).as(Integer.class).orElse(100);
        queryParams.put("limit", String.valueOf(Math.min(100, Math.max(1, limit))));

        String sortVal = runContext.render(this.sort).as(String.class).orElse("-id");
        queryParams.put("sort", sortVal);

        java.util.List<PennylaneFilter> filterList = new ArrayList<>();

        if (this.dateFrom != null) {
            runContext.render(this.dateFrom).as(String.class).ifPresent(d ->
                filterList.add(PennylaneFilter.builder().field("date").operator("gteq").value(d).build())
            );
        }

        if (this.dateTo != null) {
            runContext.render(this.dateTo).as(String.class).ifPresent(d ->
                filterList.add(PennylaneFilter.builder().field("date").operator("lteq").value(d).build())
            );
        }

        if (this.supplierId != null) {
            runContext.render(this.supplierId).as(Long.class).ifPresent(id ->
                filterList.add(PennylaneFilter.builder().field("supplier_id").operator("eq").value(id).build())
            );
        }

        if (this.paymentStatus != null) {
            runContext.render(this.paymentStatus).as(String.class).ifPresent(st ->
                filterList.add(PennylaneFilter.builder().field("payment_status").operator("eq").value(st).build())
            );
        }

        if (this.filter != null) {
            String rawFilter = runContext.render(this.filter).as(String.class).orElse(null);
            if (rawFilter != null && !rawFilter.isBlank()) {
                java.util.List<PennylaneFilter> parsed = MAPPER.readValue(
                    rawFilter,
                    new TypeReference<java.util.List<PennylaneFilter>>() {}
                );
                filterList.addAll(parsed);
            }
        }

        if (!filterList.isEmpty()) {
            queryParams.put("filter", MAPPER.writeValueAsString(filterList));
        }

        Integer max = runContext.render(this.maxRecords).as(Integer.class).orElse(null);
        java.util.List<SupplierInvoice> items = paginate(
            runContext,
            "supplier_invoices",
            queryParams,
            SupplierInvoice.class,
            max
        );

        FetchResult<SupplierInvoice> result = fetchOutput(runContext, this.fetchType, items);

        return Output.builder()
            .rows(result.rows())
            .row(result.row())
            .uri(result.uri())
            .count(result.count())
            .build();
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(title = "List of supplier invoices (populated when fetchType is FETCH)")
        private final java.util.List<SupplierInvoice> rows;

        @Schema(title = "First supplier invoice (populated when fetchType is FETCH_ONE)")
        private final SupplierInvoice row;

        @Schema(title = "URI of the stored .ion internal storage file (populated when fetchType is STORE)")
        private final URI uri;

        @Schema(title = "Total number of supplier invoices retrieved")
        private final Integer count;
    }
}
