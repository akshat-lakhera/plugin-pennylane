package io.kestra.plugin.pennylane.customerinvoices;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.conditions.ConditionContext;
import io.kestra.core.models.executions.Execution;
import io.kestra.core.models.triggers.TriggerContext;
import io.kestra.core.models.triggers.TriggerService;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.pennylane.AbstractPennylaneTask;
import io.kestra.plugin.pennylane.AbstractPennylaneTrigger;
import io.kestra.plugin.pennylane.PennylaneWatermark;
import io.kestra.plugin.pennylane.models.Changelog;
import io.kestra.plugin.pennylane.models.CustomerInvoice;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

import java.time.Duration;
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
    title = "Trigger when a Pennylane customer invoice is paid",
    description = "Polls GET /changelogs/customer_invoices, fetches each inserted or updated invoice, and keeps those with paid=true. " +
        "`paid` is a response field, not a list filter. A namespace KV watermark advances on every poll after the changelog scan is fully paged, " +
        "including polls that find no paid invoice. Deletes and failed fetches are not emitted."
)
@Plugin(
    examples = {
        @Example(
            title = "React when a customer invoice is paid",
            full = true,
            code = """
                id: pennylane_on_customer_invoice_paid
                namespace: company.finance

                triggers:
                  - id: on_invoice_paid
                    type: io.kestra.plugin.pennylane.customerinvoices.CustomerInvoicePaidTrigger
                    apiToken: "{{ secret('PENNYLANE_API_TOKEN') }}"
                    interval: PT10M

                tasks:
                  - id: notify
                    type: io.kestra.plugin.core.log.Log
                    message: "Customer invoice {{ trigger.invoice.id }} has been paid"
                """
        )
    }
)
public class CustomerInvoicePaidTrigger extends AbstractPennylaneTrigger {

    @Override
    protected Duration defaultInterval() {
        return Duration.ofMinutes(10);
    }

    @Override
    public Optional<Execution> evaluate(ConditionContext conditionContext, TriggerContext context) throws Exception {
        RunContext runContext = conditionContext.getRunContext();
        String rApiToken = AbstractPennylaneTask.renderApiToken(runContext, this.apiToken);
        String rBaseUrl = AbstractPennylaneTask.renderBaseUrl(runContext, this.baseUrl);
        String namespace = conditionContext.getFlow().getNamespace();
        String watermarkKey = PennylaneWatermark.key(conditionContext.getFlow().getId(), this.getId());
        PennylaneWatermark.State previous = PennylaneWatermark.load(runContext, namespace, watermarkKey);
        String lookback = PennylaneWatermark.initialStart(context.getDate(), getInterval());

        AbstractPennylaneTask.ChangelogSync sync = AbstractPennylaneTask.syncChangelogs(
            runContext,
            this.options,
            rApiToken,
            rBaseUrl,
            "customer_invoices",
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

        List<CustomerInvoice> invoices = new ArrayList<>();
        for (Long id : latestUnseen.keySet()) {
            CustomerInvoice invoice = AbstractPennylaneTask.fetchById(
                runContext,
                this.options,
                rApiToken,
                rBaseUrl,
                "customer_invoices/" + id,
                CustomerInvoice.class
            );
            if (invoice != null && Boolean.TRUE.equals(invoice.getPaid())) {
                invoices.add(invoice);
            }
        }

        Optional<Execution> execution = Optional.empty();
        if (!invoices.isEmpty()) {
            Map<String, Object> outputs = new LinkedHashMap<>();
            outputs.put("invoice", invoices.getLast());
            outputs.put("invoices", invoices);
            outputs.put("changeCount", sync.unseen().size());
            execution = Optional.of(TriggerService.generateExecution(this, conditionContext, context, outputs));
            runContext.logger().info(
                "Pennylane CustomerInvoicePaidTrigger fired: {} paid invoice(s)",
                invoices.size()
            );
        }

        PennylaneWatermark.save(runContext, namespace, watermarkKey, sync.next());
        return execution;
    }
}
