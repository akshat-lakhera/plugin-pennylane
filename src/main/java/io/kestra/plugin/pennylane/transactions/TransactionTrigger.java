package io.kestra.plugin.pennylane.transactions;

import io.kestra.core.http.HttpRequest;
import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.conditions.ConditionContext;
import io.kestra.core.models.executions.Execution;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.triggers.AbstractTrigger;
import io.kestra.core.models.triggers.PollingTriggerInterface;
import io.kestra.core.models.triggers.TriggerContext;
import io.kestra.core.models.triggers.TriggerService;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.pennylane.AbstractPennylaneTask;
import io.kestra.plugin.pennylane.models.PennylaneFilter;
import io.kestra.plugin.pennylane.models.PennylanePage;
import io.kestra.plugin.pennylane.models.Transaction;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

import java.net.URI;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Trigger on new Pennylane bank transactions",
    description = "Polls Pennylane bank transactions at a configurable interval. " +
        "By default polls for uncategorized transactions on all or a specific bank account. " +
        "Fires one execution per poll cycle when new transactions are detected. " +
        "The most recent transaction is available as `trigger.transaction` in downstream tasks."
)
@Plugin(
    examples = {
        @Example(
            title = "Trigger on new uncategorized transactions for a specific bank account",
            full = true,
            code = """
                id: pennylane_on_new_transaction
                namespace: company.finance

                triggers:
                  - id: on_transaction
                    type: io.kestra.plugin.pennylane.transactions.TransactionTrigger
                    apiToken: "{{ secret('PENNYLANE_API_TOKEN') }}"
                    interval: PT5M
                    categorized: false

                tasks:
                  - id: process
                    type: io.kestra.plugin.core.log.Log
                    message: "New transaction {{ trigger.transaction.id }} for {{ trigger.transaction.amount }}"
                """
        )
    }
)
public class TransactionTrigger extends AbstractTrigger implements PollingTriggerInterface {

    @Schema(
        title = "Pennylane API token",
        description = "Company or firm API token used to authenticate against the Pennylane API."
    )
    @PluginProperty(secret = true, group = "connection")
    @ToString.Exclude
    private Property<String> apiToken;

    @Schema(
        title = "Pennylane API base URL",
        description = "Base endpoint URL for Pennylane API calls."
    )
    @Builder.Default
    @PluginProperty(group = "connection")
    private Property<String> baseUrl = Property.ofValue(AbstractPennylaneTask.DEFAULT_BASE_URL);

    @Schema(
        title = "Bank account ID filter",
        description = "Filters transactions belonging to a specific bank account. When omitted, all bank accounts are monitored."
    )
    @PluginProperty(group = "processing")
    private Property<Long> bankAccountId;

    @Schema(
        title = "Categorized filter",
        description = "When set to false, only uncategorized (unmatched) transactions are returned. " +
            "When set to true, only categorized transactions are returned. Omit for all transactions."
    )
    @PluginProperty(group = "processing")
    private Property<Boolean> categorized;

    @Schema(
        title = "Polling interval",
        description = "How frequently to poll Pennylane for new transactions. ISO-8601 duration. Defaults to PT5M."
    )
    @Builder.Default
    @PluginProperty(group = "advanced")
    private Duration interval = Duration.ofMinutes(5);

    @Override
    public Duration getInterval() {
        return this.interval;
    }

    @Override
    public Optional<Execution> evaluate(ConditionContext conditionContext, TriggerContext context) throws Exception {
        RunContext runContext = conditionContext.getRunContext();

        String token = AbstractPennylaneTask.renderApiToken(runContext, this.apiToken);
        String baseUrlStr = AbstractPennylaneTask.renderBaseUrl(runContext, this.baseUrl);

        // Compute since timestamp: last evaluation date minus one interval
        String since = context.getDate()
            .minus(this.interval)
            .withZoneSameInstant(ZoneOffset.UTC)
            .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);

        List<PennylaneFilter> filterList = new ArrayList<>();

        // Filter by updated_at since last poll
        filterList.add(PennylaneFilter.builder()
            .field("updated_at")
            .operator("gteq")
            .value(since)
            .build()
        );

        if (this.bankAccountId != null) {
            runContext.render(this.bankAccountId).as(Long.class).ifPresent(id ->
                filterList.add(PennylaneFilter.builder()
                    .field("bank_account_id")
                    .operator("eq")
                    .value(id)
                    .build())
            );
        }

        if (this.categorized != null) {
            runContext.render(this.categorized).as(Boolean.class).ifPresent(cat ->
                filterList.add(PennylaneFilter.builder()
                    .field("categorized")
                    .operator("eq")
                    .value(cat)
                    .build())
            );
        }

        Map<String, String> queryParams = new LinkedHashMap<>();
        queryParams.put("limit", "100");
        queryParams.put("sort", "-id");
        queryParams.put("filter", AbstractPennylaneTask.MAPPER.writeValueAsString(filterList));

        String url = AbstractPennylaneTask.buildUriWithParams(baseUrlStr, "transactions", queryParams);

        var requestBuilder = HttpRequest.builder()
            .uri(URI.create(url))
            .method("GET");

        var pageType = AbstractPennylaneTask.MAPPER
            .getTypeFactory()
            .constructParametricType(PennylanePage.class, Transaction.class);

        @SuppressWarnings("unchecked")
        PennylanePage<Transaction> page = (PennylanePage<Transaction>) AbstractPennylaneTask.request(
            runContext,
            null,
            token,
            requestBuilder,
            pageType
        ).getBody();

        List<Transaction> items = page != null && page.getItems() != null ? page.getItems() : List.of();

        if (items.isEmpty()) {
            return Optional.empty();
        }

        Transaction latest = items.get(0);

        Map<String, Object> outputs = new LinkedHashMap<>();
        outputs.put("transaction", latest);
        outputs.put("transactions", items);
        outputs.put("transactionCount", items.size());

        Execution execution = TriggerService.generateExecution(this, conditionContext, context, outputs);

        runContext.logger().info(
            "Pennylane TransactionTrigger fired: {} new transaction(s) since {}",
            items.size(),
            since
        );

        return Optional.of(execution);
    }
}
