package io.kestra.plugin.pennylane.accounting.ledgerentrylines;

import com.fasterxml.jackson.core.type.TypeReference;
import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.models.tasks.common.FetchType;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.pennylane.AbstractPennylaneTask;
import io.kestra.plugin.pennylane.models.LedgerEntryLine;
import io.kestra.plugin.pennylane.models.PennylaneFilter;
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
    title = "List Pennylane ledger entry lines",
    description = "Retrieves ledger entry lines from Pennylane with cursor pagination and filtering by date or ledger account."
)
@Plugin(
    examples = {
        @Example(
            title = "List ledger entry lines for Q1 2026",
            full = true,
            code = """
                id: pennylane_ledger_entry_lines
                namespace: company.finance

                tasks:
                  - id: entry_lines
                    type: io.kestra.plugin.pennylane.accounting.ledgerentrylines.List
                    apiToken: "{{ secret('PENNYLANE_API_TOKEN') }}"
                    dateFrom: "2026-01-01"
                    dateTo: "2026-03-31"
                    fetchType: STORE
                """
        )
    }
)
public class List extends AbstractPennylaneTask implements RunnableTask<List.Output> {

    @Schema(
        title = "Entry start date",
        description = "Filters ledger entry lines with a date on or after this value (format YYYY-MM-DD)."
    )
    @PluginProperty(group = "processing")
    private Property<String> dateFrom;

    @Schema(
        title = "Entry end date",
        description = "Filters ledger entry lines with a date on or before this value (format YYYY-MM-DD)."
    )
    @PluginProperty(group = "processing")
    private Property<String> dateTo;

    @Schema(
        title = "Ledger account identifier",
        description = "Filters entry lines for a specific ledger account ID."
    )
    @PluginProperty(group = "processing")
    private Property<Long> ledgerAccountId;

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
        description = "Defines how results are emitted: FETCH, FETCH_ONE, STORE, or NONE. Defaults to FETCH."
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

        if (this.ledgerAccountId != null) {
            runContext.render(this.ledgerAccountId).as(Long.class).ifPresent(id ->
                filterList.add(PennylaneFilter.builder().field("ledger_account_id").operator("eq").value(id).build())
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
        java.util.List<LedgerEntryLine> items = paginate(
            runContext,
            "ledger_entry_lines",
            queryParams,
            LedgerEntryLine.class,
            max
        );

        FetchResult<LedgerEntryLine> result = fetchOutput(runContext, this.fetchType, items);

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
        @Schema(title = "List of ledger entry lines (populated when fetchType is FETCH)")
        private final java.util.List<LedgerEntryLine> rows;

        @Schema(title = "First ledger entry line (populated when fetchType is FETCH_ONE)")
        private final LedgerEntryLine row;

        @Schema(title = "URI of the stored .ion internal storage file (populated when fetchType is STORE)")
        private final URI uri;

        @Schema(title = "Total number of ledger entry lines retrieved")
        private final Integer count;
    }
}
