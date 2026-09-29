package io.kestra.plugin.pennylane.accounting.trialbalance;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.models.tasks.common.FetchType;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.pennylane.AbstractPennylaneTask;
import io.kestra.plugin.pennylane.models.TrialBalanceLine;
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
import java.util.List;
import java.util.Map;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Get Pennylane trial balance",
    description = "Retrieves the trial balance for a required period. " +
        "GET /trial_balance returns a cursor page of rows with number, formatted_number, label, debits, and credits. " +
        "Requires the trial_balance:readonly scope."
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
                    fetchType: STORE
                """
        )
    }
)
public class Get extends AbstractPennylaneTask implements RunnableTask<Get.Output> {

    @Schema(
        title = "Period start date",
        description = "Required start of the accounting period (YYYY-MM-DD), sent as period_start."
    )
    @NotNull
    @PluginProperty(group = "processing")
    private Property<String> periodStart;

    @Schema(
        title = "Period end date",
        description = "Required end of the accounting period (YYYY-MM-DD), sent as period_end."
    )
    @NotNull
    @PluginProperty(group = "processing")
    private Property<String> periodEnd;

    @Schema(
        title = "Page size",
        description = "Number of trial balance rows per request (1 to 1000). Defaults to 100.",
        minimum = "1",
        maximum = "1000"
    )
    @Builder.Default
    @PluginProperty(group = "processing")
    private Property<Integer> pageSize = Property.ofValue(DEFAULT_PAGE_SIZE);

    @Schema(
        title = "Maximum records",
        description = "Maximum total number of rows to retrieve across all pages. Omit to fetch every row. Must be at least 1 when set.",
        minimum = "1"
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
        String rPeriodStart = runContext.render(this.periodStart).as(String.class)
            .orElseThrow(() -> new IllegalArgumentException("periodStart is required"));
        String rPeriodEnd = runContext.render(this.periodEnd).as(String.class)
            .orElseThrow(() -> new IllegalArgumentException("periodEnd is required"));
        int rPageSize = renderPageSize(runContext, this.pageSize, MAX_TRIAL_BALANCE_PAGE_SIZE);
        Integer rMaxRecords = renderMaxRecords(runContext, this.maxRecords);

        Map<String, String> queryParams = new LinkedHashMap<>();
        queryParams.put("period_start", rPeriodStart);
        queryParams.put("period_end", rPeriodEnd);
        queryParams.put("limit", String.valueOf(rPageSize));

        FetchResult<TrialBalanceLine> result = drain(
            runContext,
            "trial_balance",
            queryParams,
            TrialBalanceLine.class,
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
        @Schema(title = "Trial balance rows (populated when fetchType is FETCH)")
        private final List<TrialBalanceLine> rows;

        @Schema(title = "First trial balance row (populated when fetchType is FETCH_ONE)")
        private final TrialBalanceLine row;

        @Schema(title = "URI of the stored .ion internal storage file (populated when fetchType is STORE)")
        private final URI uri;

        @Schema(title = "Total number of trial balance rows retrieved")
        private final Integer count;
    }
}
