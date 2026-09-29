package io.kestra.plugin.pennylane.transactions;

import io.kestra.core.http.HttpRequest;
import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
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

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Get a Pennylane bank transaction",
    description = "Retrieves a single bank transaction by its ID."
)
@Plugin(
    examples = {
        @Example(
            title = "Get a transaction by ID",
            full = true,
            code = """
                id: get_pennylane_transaction
                namespace: company.finance

                tasks:
                  - id: get_tx
                    type: io.kestra.plugin.pennylane.transactions.Get
                    apiToken: "{{ secret('PENNYLANE_API_TOKEN') }}"
                    transactionId: 998877
                """
        )
    }
)
public class Get extends AbstractPennylaneTask implements RunnableTask<Get.Output> {

    @Schema(
        title = "Transaction ID",
        description = "Unique identifier of the bank transaction to retrieve."
    )
    @NotNull
    @PluginProperty(group = "main")
    private Property<Long> transactionId;

    @Override
    public Output run(RunContext runContext) throws Exception {
        Long idVal = runContext.render(this.transactionId).as(Long.class).orElseThrow(
            () -> new IllegalArgumentException("transactionId is required")
        );

        String baseUrlStr = renderBaseUrl(runContext);
        String url = join(baseUrlStr, "transactions/" + idVal);

        var requestBuilder = HttpRequest.builder()
            .uri(URI.create(url))
            .method("GET");

        Transaction transaction = request(runContext, requestBuilder, Transaction.class).getBody();

        return Output.builder()
            .row(transaction)
            .build();
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(title = "The retrieved bank transaction")
        private final Transaction row;
    }
}
