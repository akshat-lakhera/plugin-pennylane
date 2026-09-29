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

        Integer max = runContext.render(this.maxRecords).as(Integer.class).orElse(null);
        java.util.List<BankAccount> items = paginate(
            runContext,
            "bank_accounts",
            queryParams,
            BankAccount.class,
            max
        );

        FetchResult<BankAccount> result = fetchOutput(runContext, this.fetchType, items);

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

        @Schema(title = "Total number of bank accounts retrieved")
        private final Integer count;
    }
}
