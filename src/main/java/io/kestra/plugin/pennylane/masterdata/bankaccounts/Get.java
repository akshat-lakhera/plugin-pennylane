package io.kestra.plugin.pennylane.masterdata.bankaccounts;

import io.kestra.core.http.HttpRequest;
import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.pennylane.AbstractPennylaneTask;
import io.kestra.plugin.pennylane.models.BankAccount;
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
    title = "Get a Pennylane bank account",
    description = "Retrieves a single bank account by ID."
)
@Plugin(
    examples = {
        @Example(
            title = "Get a bank account by ID",
            full = true,
            code = """
                id: get_pennylane_bank_account
                namespace: company.finance

                tasks:
                  - id: get_acc
                    type: io.kestra.plugin.pennylane.masterdata.bankaccounts.Get
                    apiToken: "{{ secret('PENNYLANE_API_TOKEN') }}"
                    bankAccountId: 1122
                """
        )
    }
)
public class Get extends AbstractPennylaneTask implements RunnableTask<Get.Output> {

    @Schema(
        title = "Bank account ID",
        description = "Unique identifier of the bank account to retrieve."
    )
    @NotNull
    @PluginProperty(group = "main")
    private Property<Long> bankAccountId;

    @Override
    public Output run(RunContext runContext) throws Exception {
        Long rBankAccountId = runContext.render(this.bankAccountId).as(Long.class).orElseThrow(
            () -> new IllegalArgumentException("bankAccountId is required")
        );

        String rBaseUrl = renderBaseUrl(runContext);
        String url = join(rBaseUrl, "bank_accounts/" + rBankAccountId);

        var requestBuilder = HttpRequest.builder()
            .uri(URI.create(url))
            .method("GET");

        BankAccount account = request(runContext, requestBuilder, BankAccount.class).getBody();

        return Output.builder()
            .row(account)
            .build();
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(title = "The retrieved bank account")
        private final BankAccount row;
    }
}
