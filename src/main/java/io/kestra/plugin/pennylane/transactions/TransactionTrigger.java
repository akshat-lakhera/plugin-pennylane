package io.kestra.plugin.pennylane.transactions;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.conditions.ConditionContext;
import io.kestra.core.models.executions.Execution;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.triggers.TriggerContext;
import io.kestra.core.models.triggers.TriggerService;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.pennylane.AbstractPennylaneTask;
import io.kestra.plugin.pennylane.AbstractPennylaneTrigger;
import io.kestra.plugin.pennylane.PennylaneWatermark;
import io.kestra.plugin.pennylane.models.Changelog;
import io.kestra.plugin.pennylane.models.PennylaneFilter;
import io.kestra.plugin.pennylane.models.Transaction;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

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
    description = "Polls GET /changelogs/transactions, then loads those transactions with an id `in` filter. " +
        "The transactions list allow-list is id, bank_account_id, journal_id, and date, so updated_at and categorized are not sent as query filters. " +
        "`categorized` is applied after fetch: the boolean is used when present, otherwise a non-empty categories array counts as categorized. " +
        "Because the Pennylane transactions list endpoint does not support filtering by `categorized`, transactions are filtered in memory. " +
        "Once evaluated, the watermark advances to avoid re-processing the same changelog events. If a transaction is subsequently categorized or uncategorized, Pennylane emits a new changelog event which this trigger will evaluate. " +
        "`bankAccountId` is sent as bank_account_id eq. A namespace KV watermark advances on every fully paged poll."
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
                    bankAccountId: 42
                    categorized: false

                tasks:
                  - id: process
                    type: io.kestra.plugin.core.log.Log
                    message: "New transaction {{ trigger.transaction.id }} for {{ trigger.transaction.amount }}"
                """
        )
    }
)
public class TransactionTrigger extends AbstractPennylaneTrigger {

    @Schema(
        title = "Bank account ID filter",
        description = "When set, fetched transactions are restricted with bank_account_id eq. This is a legal transactions list filter."
    )
    @PluginProperty(group = "processing")
    private Property<Long> bankAccountId;

    @Schema(
        title = "Categorized post-filter",
        description = "Applied in the plugin after the transactions are fetched, not as an API filter. " +
            "Note: the watermark advances past evaluated changelog events regardless of whether they match this filter. " +
            "When an existing transaction is later categorized or updated in Pennylane, a new changelog event is emitted and evaluated. " +
            "When the payload has a categorized boolean it is used. Otherwise a transaction is treated as categorized when categories is non-empty. " +
            "There is no native categorized query filter on the v2 transaction list endpoint; attachment_required and categories are the live signals."
    )
    @PluginProperty(group = "processing")
    private Property<Boolean> categorized;

    @Override
    public Optional<Execution> evaluate(ConditionContext conditionContext, TriggerContext context) throws Exception {
        RunContext runContext = conditionContext.getRunContext();
        String rApiToken = AbstractPennylaneTask.renderApiToken(runContext, this.apiToken);
        String rBaseUrl = AbstractPennylaneTask.renderBaseUrl(runContext, this.baseUrl);
        String namespace = conditionContext.getFlow().getNamespace();
        String watermarkKey = PennylaneWatermark.key(conditionContext.getFlow().getId(), this.getId());
        PennylaneWatermark.State previous = PennylaneWatermark.load(runContext, namespace, watermarkKey);
        String lookback = PennylaneWatermark.initialStart(context.getDate(), getInterval());

        Long rBankAccountId = this.bankAccountId == null
            ? null
            : runContext.render(this.bankAccountId).as(Long.class).orElse(null);
        Boolean rCategorized = this.categorized == null
            ? null
            : runContext.render(this.categorized).as(Boolean.class).orElse(null);

        AbstractPennylaneTask.ChangelogSync sync = AbstractPennylaneTask.syncChangelogs(
            runContext,
            this.options,
            rApiToken,
            rBaseUrl,
            "transactions",
            previous,
            lookback
        );

        // Re-insert on repeat so iteration order follows each id's most recent event
        Map<Long, Changelog> latestUnseen = new LinkedHashMap<>();
        for (Changelog change : sync.unseen()) {
            if (!change.deleted() && change.getId() != null) {
                latestUnseen.remove(change.getId());
                latestUnseen.put(change.getId(), change);
            }
        }

        List<Long> ids = new ArrayList<>(latestUnseen.keySet());
        Map<Long, Transaction> byId = new LinkedHashMap<>();
        for (int offset = 0; offset < ids.size(); offset += AbstractPennylaneTask.MAX_LIST_PAGE_SIZE) {
            List<Long> chunk = ids.subList(offset, Math.min(offset + AbstractPennylaneTask.MAX_LIST_PAGE_SIZE, ids.size()));
            List<PennylaneFilter> filters = new ArrayList<>();
            filters.add(PennylaneFilter.builder().field("id").operator("in").value(new ArrayList<>(chunk)).build());
            if (rBankAccountId != null) {
                filters.add(PennylaneFilter.builder().field("bank_account_id").operator("eq").value(rBankAccountId).build());
            }
            Map<String, String> queryParams = new LinkedHashMap<>();
            queryParams.put("limit", String.valueOf(AbstractPennylaneTask.MAX_LIST_PAGE_SIZE));
            queryParams.put("filter", AbstractPennylaneTask.MAPPER.writeValueAsString(filters));

            List<Transaction> batch = AbstractPennylaneTask.listAll(
                runContext,
                this.options,
                rApiToken,
                rBaseUrl,
                "transactions",
                queryParams,
                Transaction.class,
                AbstractPennylaneTask.PageMode.STANDARD,
                null
            );
            for (Transaction transaction : batch) {
                if (transaction.getId() != null) {
                    byId.put(transaction.getId(), transaction);
                }
            }
        }

        List<Transaction> matched = new ArrayList<>();
        for (Long id : ids) {
            Transaction transaction = byId.get(id);
            if (transaction == null) {
                continue;
            }
            if (rCategorized != null && transaction.isCategorizedForFilter() != rCategorized) {
                continue;
            }
            if (rBankAccountId != null) {
                Long resolved = transaction.resolvedBankAccountId();
                if (resolved != null && !resolved.equals(rBankAccountId)) {
                    continue;
                }
            }
            matched.add(transaction);
        }

        Optional<Execution> execution = Optional.empty();
        if (!matched.isEmpty()) {
            Map<String, Object> outputs = new LinkedHashMap<>();
            outputs.put("transaction", matched.getLast());
            outputs.put("transactions", matched);
            outputs.put("transactionCount", matched.size());
            execution = Optional.of(TriggerService.generateExecution(this, conditionContext, context, outputs));
            runContext.logger().info(
                "Pennylane TransactionTrigger fired: {} transaction(s) from {} changelog event(s)",
                matched.size(),
                sync.unseen().size()
            );
        }

        PennylaneWatermark.save(runContext, namespace, watermarkKey, sync.next());
        return execution;
    }
}
