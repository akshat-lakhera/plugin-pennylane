package io.kestra.plugin.pennylane.supplierinvoices;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.models.tasks.common.FetchType;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.pennylane.AbstractPennylaneTask;
import io.kestra.plugin.pennylane.models.Transaction;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
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
    title = "List matched bank transactions for a supplier invoice",
    description = "Retrieves bank transactions reconciled and matched with a specific Pennylane supplier invoice."
)
@Plugin(
    examples = {
        @Example(
            title = "List bank transactions matched to a supplier invoice",
            full = true,
            code = """
                id: pennylane_matched_transactions
                namespace: company.finance

                tasks:
                  - id: matched_transactions
                    type: io.kestra.plugin.pennylane.supplierinvoices.MatchedTransactions
                    apiToken: "{{ secret('PENNYLANE_API_TOKEN') }}"
                    invoiceId: 1001
                    fetchType: FETCH
                """
        )
    }
)
public class MatchedTransactions extends AbstractPennylaneTask implements RunnableTask<MatchedTransactions.Output> {

    @Schema(
        title = "Supplier invoice identifier",
        description = "Unique ID of the supplier invoice."
    )
    @NotNull
    @PluginProperty(group = "processing")
    private Property<Long> invoiceId;

    @Schema(
        title = "Fetch type",
        description = "Defines how results are emitted: FETCH, FETCH_ONE, STORE, or NONE. Defaults to FETCH."
    )
    @Builder.Default
    @PluginProperty(group = "processing")
    private Property<FetchType> fetchType = Property.ofValue(FetchType.FETCH);

    @Override
    public Output run(RunContext runContext) throws Exception {
        Long rInvoiceId = runContext.render(this.invoiceId).as(Long.class).orElseThrow(
            () -> new IllegalArgumentException("invoiceId is required")
        );

        Map<String, String> queryParams = new LinkedHashMap<>();
        queryParams.put("limit", String.valueOf(MAX_LIST_PAGE_SIZE));

        FetchResult<Transaction> result = drain(
            runContext,
            "supplier_invoices/" + rInvoiceId + "/matched_transactions",
            queryParams,
            Transaction.class,
            this.fetchType,
            null,
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
        @Schema(title = "List of matched bank transactions (populated when fetchType is FETCH)")
        private final java.util.List<Transaction> rows;

        @Schema(title = "First matched bank transaction (populated when fetchType is FETCH_ONE)")
        private final Transaction row;

        @Schema(title = "URI of the stored .ion internal storage file (populated when fetchType is STORE)")
        private final URI uri;

        @Schema(title = "Total number of matched bank transactions retrieved")
        private final Integer count;
    }
}
