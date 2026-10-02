package io.kestra.plugin.pennylane.accounting.ledgeraccounts;

import com.fasterxml.jackson.core.type.TypeReference;
import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.models.tasks.common.FetchType;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.pennylane.AbstractPennylaneTask;
import io.kestra.plugin.pennylane.models.LedgerAccount;
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
    title = "List Pennylane ledger accounts",
    description = "Retrieves all ledger accounts from Pennylane with cursor pagination and optional filtering."
)
@Plugin(
    examples = {
        @Example(
            title = "List all enabled ledger accounts",
            full = true,
            code = """
                id: pennylane_ledger_accounts
                namespace: company.finance

                tasks:
                  - id: ledger_accounts
                    type: io.kestra.plugin.pennylane.accounting.ledgeraccounts.List
                    apiToken: "{{ secret('PENNYLANE_API_TOKEN') }}"
                    fetchType: FETCH
                """
        )
    }
)
public class List extends AbstractPennylaneTask implements RunnableTask<List.Output> {

    @Schema(
        title = "Raw Pennylane filter DSL",
        description = "Raw JSON filter string matching Pennylane filter DSL, e.g. `[{\"field\": \"enabled\", \"operator\": \"eq\", \"value\": true}]`."
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
        FetchResult<LedgerAccount> result = drain(
            runContext,
            "ledger_accounts",
            queryParams,
            LedgerAccount.class,
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
        @Schema(title = "List of ledger accounts (populated when fetchType is FETCH)")
        private final java.util.List<LedgerAccount> rows;

        @Schema(title = "First ledger account (populated when fetchType is FETCH_ONE)")
        private final LedgerAccount row;

        @Schema(title = "URI of the stored .ion internal storage file (populated when fetchType is STORE)")
        private final URI uri;

        @Schema(
            title = "Total number of ledger accounts retrieved",
            description = "For FETCH_ONE, 1 when a row is returned and 0 when nothing matched."
        )
        private final Integer count;
    }
}
