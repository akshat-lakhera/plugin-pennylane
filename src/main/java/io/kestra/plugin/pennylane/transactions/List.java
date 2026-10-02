package io.kestra.plugin.pennylane.transactions;

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
import io.kestra.plugin.pennylane.models.Transaction;
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
    title = "List Pennylane bank transactions",
    description = "Retrieves bank transactions from Pennylane with cursor pagination. " +
        "Legal list filters are id, bank_account_id, journal_id, and date. " +
        "updated_at, categorized, and amount are not query filters; use changelogs.List or TransactionTrigger for incremental sync."
)
@Plugin(
    examples = {
        @Example(
            title = "List recent bank transactions for an account",
            full = true,
            code = """
                id: pennylane_bank_transactions
                namespace: company.finance

                tasks:
                  - id: transactions
                    type: io.kestra.plugin.pennylane.transactions.List
                    apiToken: "{{ secret('PENNYLANE_API_TOKEN') }}"
                    bankAccountId: 9876
                    dateFrom: "2026-01-01"
                    fetchType: FETCH
                """
        )
    }
)
public class List extends AbstractPennylaneTask implements RunnableTask<List.Output> {

    @Schema(
        title = "Bank account identifier",
        description = "Filters transactions belonging to a specific bank account ID."
    )
    @PluginProperty(group = "processing")
    private Property<Long> bankAccountId;

    @Schema(
        title = "Journal identifier",
        description = "Filters transactions belonging to a specific journal ID."
    )
    @PluginProperty(group = "processing")
    private Property<Long> journalId;

    @Schema(
        title = "Transaction start date",
        description = "Filters transactions on or after this date (format YYYY-MM-DD)."
    )
    @PluginProperty(group = "processing")
    private Property<String> dateFrom;

    @Schema(
        title = "Transaction end date",
        description = "Filters transactions on or before this date (format YYYY-MM-DD)."
    )
    @PluginProperty(group = "processing")
    private Property<String> dateTo;

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

        if (this.bankAccountId != null) {
            runContext.render(this.bankAccountId).as(Long.class).ifPresent(id ->
                filterList.add(PennylaneFilter.builder().field("bank_account_id").operator("eq").value(id).build())
            );
        }

        if (this.journalId != null) {
            runContext.render(this.journalId).as(Long.class).ifPresent(id ->
                filterList.add(PennylaneFilter.builder().field("journal_id").operator("eq").value(id).build())
            );
        }

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
        FetchResult<Transaction> result = drain(
            runContext,
            "transactions",
            queryParams,
            Transaction.class,
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
        @Schema(title = "List of transactions (populated when fetchType is FETCH)")
        private final java.util.List<Transaction> rows;

        @Schema(title = "First transaction (populated when fetchType is FETCH_ONE)")
        private final Transaction row;

        @Schema(title = "URI of the stored .ion internal storage file (populated when fetchType is STORE)")
        private final URI uri;

        @Schema(
            title = "Total number of transactions retrieved",
            description = "For FETCH_ONE, 1 when a row is returned and 0 when nothing matched."
        )
        private final Integer count;
    }
}
