package io.kestra.plugin.pennylane.accounting.trialbalance;

import io.kestra.core.http.HttpRequest;
import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.pennylane.AbstractPennylaneTask;
import io.kestra.plugin.pennylane.models.TrialBalanceLine;
import io.kestra.plugin.pennylane.models.TrialBalanceResponse;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Get Pennylane trial balance",
    description = "Retrieves the trial balance for the current company over the given accounting period. " +
        "Returns one row per ledger account with debit, credit, and net balance totals."
)
@Plugin(
    examples = {
        @Example(
            title = "Get trial balance for Q1 2026",
            full = true,
            code = """
                id: pennylane_trial_balance
                namespace: company.finance

                tasks:
                  - id: trial_balance
                    type: io.kestra.plugin.pennylane.accounting.trialbalance.Get
                    apiToken: "{{ secret('PENNYLANE_API_TOKEN') }}"
                    periodStart: "2026-01-01"
                    periodEnd: "2026-03-31"
                """
        )
    }
)
public class Get extends AbstractPennylaneTask implements RunnableTask<Get.Output> {

    @Schema(
        title = "Period start date",
        description = "Start of the accounting period for the trial balance (format YYYY-MM-DD)."
    )
    @PluginProperty(group = "processing")
    private Property<String> periodStart;

    @Schema(
        title = "Period end date",
        description = "End of the accounting period for the trial balance (format YYYY-MM-DD)."
    )
    @PluginProperty(group = "processing")
    private Property<String> periodEnd;

    @Override
    public Output run(RunContext runContext) throws Exception {
        Map<String, String> queryParams = new LinkedHashMap<>();

        if (this.periodStart != null) {
            runContext.render(this.periodStart).as(String.class).ifPresent(d ->
                queryParams.put("period_start", d)
            );
        }

        if (this.periodEnd != null) {
            runContext.render(this.periodEnd).as(String.class).ifPresent(d ->
                queryParams.put("period_end", d)
            );
        }

        String baseUrlStr = renderBaseUrl(runContext);
        String url = buildUriWithParams(baseUrlStr, "trial_balance", queryParams);

        var requestBuilder = HttpRequest.builder()
            .uri(URI.create(url))
            .method("GET");

        TrialBalanceResponse response = request(runContext, requestBuilder, TrialBalanceResponse.class).getBody();

        List<TrialBalanceLine> rows = response != null && response.getLedgerAccounts() != null
            ? response.getLedgerAccounts()
            : java.util.Collections.emptyList();

        return Output.builder()
            .rows(rows)
            .count(rows.size())
            .build();
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(title = "List of trial balance rows, one per ledger account")
        private final List<TrialBalanceLine> rows;

        @Schema(title = "Total number of ledger account rows in the trial balance")
        private final Integer count;
    }
}
