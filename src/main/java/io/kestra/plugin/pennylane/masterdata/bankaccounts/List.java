package io.kestra.plugin.pennylane.masterdata.bankaccounts;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.models.tasks.common.FetchType;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.pennylane.AbstractPennylaneTask;
import io.kestra.plugin.pennylane.models.BankAccount;
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
import java.util.LinkedHashMap;
import java.util.Map;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "List Pennylane bank accounts",
    description = "Retrieves bank accounts and current balances from Pennylane with cursor pagination."
)
@Plugin(
    examples = {
        @Example(
            title = "List bank accounts in Pennylane",
            full = true,
            code = """
                id: pennylane_bank_accounts
                namespace: company.finance

                tasks:
                  - id: bank_accounts
                    type: io.kestra.plugin.pennylane.masterdata.bankaccounts.List
                    apiToken: "{{ secret('PENNYLANE_API_TOKEN') }}"
                    fetchType: FETCH
                """
        )
    }
)
public class List extends AbstractPennylaneTask implements RunnableTask<List.Output> {

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

        Integer rMaxRecords = renderMaxRecords(runContext, this.maxRecords);
        FetchResult<BankAccount> result = drain(
            runContext,
            "bank_accounts",
            queryParams,
            BankAccount.class,
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
        @Schema(title = "List of bank accounts (populated when fetchType is FETCH)")
        private final java.util.List<BankAccount> rows;

        @Schema(title = "First bank account (populated when fetchType is FETCH_ONE)")
        private final BankAccount row;

        @Schema(title = "URI of the stored .ion internal storage file (populated when fetchType is STORE)")
        private final URI uri;

        @Schema(
            title = "Total number of bank accounts retrieved",
            description = "For FETCH_ONE, 1 when a row is returned and 0 when nothing matched."
        )
        private final Integer count;
    }
}
