package io.kestra.plugin.pennylane.customerinvoices;

import io.kestra.core.http.client.configurations.HttpConfiguration;
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
import io.kestra.plugin.pennylane.PennylaneWatermark;
import io.kestra.plugin.pennylane.models.Changelog;
import io.kestra.plugin.pennylane.models.CustomerInvoice;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
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
public class CustomerInvoicePaidTrigger extends AbstractTrigger implements PollingTriggerInterface {

    @Schema(
        title = "Pennylane API token",
        description = "Company or firm API token used to authenticate against the Pennylane API."
    )
    @NotNull
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
        title = "HTTP client options",
        description = "Optional HTTP client configuration (timeouts, proxy, SSL) applied to every request."
    )
    @PluginProperty(group = "advanced")
    private HttpConfiguration options;

    @Schema(
        title = "Polling interval",
        description = "How frequently to poll the Pennylane changelog for paid customer invoices. ISO-8601 duration. Defaults to PT10M."
    )
    @Builder.Default
    @PluginProperty(group = "advanced")
    private Duration interval = Duration.ofMinutes(10);

    @Override
    public Duration getInterval() {
        return this.interval;
    }

    @Override
    public Optional<Execution> evaluate(ConditionContext conditionContext, TriggerContext context) throws Exception {
        RunContext runContext = conditionContext.getRunContext();
        String rApiToken = AbstractPennylaneTask.renderApiToken(runContext, this.apiToken);
        String rBaseUrl = AbstractPennylaneTask.renderBaseUrl(runContext, this.baseUrl);
        String namespace = conditionContext.getFlow().getNamespace();
        String watermarkKey = PennylaneWatermark.key(conditionContext.getFlow().getId(), this.getId());
        PennylaneWatermark.State previous = PennylaneWatermark.load(runContext, namespace, watermarkKey);
        String lookback = PennylaneWatermark.initialStart(context.getDate(), this.interval);

        AbstractPennylaneTask.ChangelogSync sync = AbstractPennylaneTask.syncChangelogs(
            runContext,
            this.options,
            rApiToken,
            rBaseUrl,
            "customer_invoices",
            previous,
            lookback
        );

        List<CustomerInvoice> invoices = new ArrayList<>();
        for (Changelog change : sync.unseen()) {
            if (change.deleted() || change.getId() == null) {
                continue;
            }
            CustomerInvoice invoice = AbstractPennylaneTask.fetchById(
                runContext,
                this.options,
                rApiToken,
                rBaseUrl,
                "customer_invoices/" + change.getId(),
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
