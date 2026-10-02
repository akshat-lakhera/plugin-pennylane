package io.kestra.plugin.pennylane.customerinvoices;

import com.fasterxml.jackson.core.type.TypeReference;
import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.models.tasks.common.FetchType;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.pennylane.AbstractPennylaneTask;
import io.kestra.plugin.pennylane.models.CustomerInvoice;
import io.kestra.plugin.pennylane.models.PennylaneFilter;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
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
    title = "List Pennylane customer invoices",
    description = "Retrieves customer invoices and credit notes from Pennylane with cursor pagination and filtering."
)
@Plugin(
    examples = {
        @Example(
            title = "List finalized customer invoices issued this month",
            full = true,
            code = """
                id: pennylane_customer_invoices
                namespace: company.finance

                tasks:
                  - id: customer_invoices
                    type: io.kestra.plugin.pennylane.customerinvoices.List
                    apiToken: "{{ secret('PENNYLANE_API_TOKEN') }}"
                    dateFrom: "2026-01-01"
                    draft: false
                    fetchType: FETCH
                """
        )
    }
)
public class List extends AbstractPennylaneTask implements RunnableTask<List.Output> {

    @Schema(
        title = "Invoice issue start date",
        description = "Filters customer invoices issued on or after this date (format YYYY-MM-DD)."
    )
    @PluginProperty(group = "processing")
    private Property<String> dateFrom;

    @Schema(
        title = "Invoice issue end date",
        description = "Filters customer invoices issued on or before this date (format YYYY-MM-DD)."
    )
    @PluginProperty(group = "processing")
    private Property<String> dateTo;

    @Schema(
        title = "Customer identifier",
        description = "Filters invoices for a specific customer ID."
    )
    @PluginProperty(group = "processing")
    private Property<Long> customerId;

    @Schema(
        title = "Draft state filter",
        description = "Filters invoices by draft status: true for drafts only, false for validated invoices."
    )
    @PluginProperty(group = "processing")
    private Property<Boolean> draft;

    @Schema(
        title = "Credit note filter",
        description = "Filters by credit note status: true for credit notes only, false for standard invoices."
    )
    @PluginProperty(group = "processing")
    private Property<Boolean> creditNote;

    @Schema(
        title = "Raw Pennylane filter DSL",
        description = "Raw JSON filter string matching Pennylane filter DSL."
    )
    @PluginProperty(group = "processing")
    private Property<String> filter;

    @Schema(
        title = "Sort order",
        description = "Attribute to sort by. Defaults to '-id'."
    )
    @Builder.Default
    @PluginProperty(group = "processing")
    private Property<String> sort = Property.ofValue("-id");

    @Schema(
        title = "Page size",
        description = "Number of items per request page. Must be between 1 and 100. Defaults to 100.",
        minimum = "1",
        maximum = "100"
    )
    @Builder.Default
    @PluginProperty(group = "processing")
    private Property<@Min(1) @Max(100) Integer> pageSize = Property.ofValue(100);

    @Schema(
        title = "Maximum records",
        description = "Maximum total number of records to retrieve across all pages. Omit to fetch all matching records. Must be at least 1 when set. No limit by default: pagination follows the API until the last page. Set `maxRecords` to cap the number of records, especially with `fetchType: FETCH`, which keeps all rows in memory; prefer `STORE` for large exports.",
        minimum = "1"
    )
    @PluginProperty(group = "processing")
    private Property<@Min(1) Integer> maxRecords;

    @Schema(
        title = "Fetch type",
        description = "Defines how results are emitted: FETCH, FETCH_ONE, STORE, or NONE. FETCH_ONE stops at the first matching record and returns it as `row` (`count` is 1, or 0 when nothing matches). Defaults to FETCH."
    )
    @Builder.Default
    @PluginProperty(group = "processing")
    private Property<FetchType> fetchType = Property.ofValue(FetchType.FETCH);

    @Override
    public Output run(RunContext runContext) throws Exception {
        Map<String, String> queryParams = new LinkedHashMap<>();

        int rPageSize = renderPageSize(runContext, this.pageSize, MAX_LIST_PAGE_SIZE);
        queryParams.put("limit", String.valueOf(rPageSize));

        String rSort = runContext.render(this.sort).as(String.class).orElse("-id");
        queryParams.put("sort", rSort);

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

        if (this.customerId != null) {
            runContext.render(this.customerId).as(Long.class).ifPresent(id ->
                filterList.add(PennylaneFilter.builder().field("customer_id").operator("eq").value(id).build())
            );
        }

        if (this.draft != null) {
            runContext.render(this.draft).as(Boolean.class).ifPresent(dr ->
                filterList.add(PennylaneFilter.builder().field("draft").operator("eq").value(dr).build())
            );
        }

        if (this.creditNote != null) {
            runContext.render(this.creditNote).as(Boolean.class).ifPresent(cn ->
                filterList.add(PennylaneFilter.builder().field("credit_note").operator("eq").value(cn).build())
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

        Integer rMaxRecords = renderMaxRecords(runContext, this.maxRecords);
        FetchResult<CustomerInvoice> result = drain(
            runContext,
            "customer_invoices",
            queryParams,
            CustomerInvoice.class,
            this.fetchType,
            rMaxRecords,
            PageMode.STANDARD,
            null
        );

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
        @Schema(title = "List of customer invoices (populated when fetchType is FETCH)")
        private final java.util.List<CustomerInvoice> rows;

        @Schema(title = "First customer invoice (populated when fetchType is FETCH_ONE)")
        private final CustomerInvoice row;

        @Schema(title = "URI of the stored .ion internal storage file (populated when fetchType is STORE)")
        private final URI uri;

        @Schema(
            title = "Total number of customer invoices retrieved",
            description = "For FETCH_ONE, 1 when a row is returned and 0 when nothing matched."
        )
        private final Integer count;
    }
}
