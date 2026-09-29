package io.kestra.plugin.pennylane.supplierinvoices;

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
import io.kestra.plugin.pennylane.models.SupplierInvoice;
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
    title = "Trigger on new Pennylane supplier invoices",
    description = "Polls GET /changelogs/supplier_invoices and fetches each inserted or updated invoice. " +
        "A namespace KV watermark stores the last fully paged processed_at and advances on every poll, including empty polls. " +
        "One execution contains every new invoice from the scan. Deletes are skipped and a failed fetch is not emitted."
)
@Plugin(
    examples = {
        @Example(
            title = "React when a new supplier invoice is registered",
            full = true,
            code = """
                id: pennylane_on_supplier_invoice
                namespace: company.finance

                triggers:
                  - id: on_supplier_invoice
                    type: io.kestra.plugin.pennylane.supplierinvoices.SupplierInvoiceTrigger
                    apiToken: "{{ secret('PENNYLANE_API_TOKEN') }}"
                    interval: PT5M

                tasks:
                  - id: notify
                    type: io.kestra.plugin.core.log.Log
                    message: "New supplier invoice {{ trigger.invoice.id }} for {{ trigger.invoice.amount }}"
                """
        )
    }
)
public class SupplierInvoiceTrigger extends AbstractTrigger implements PollingTriggerInterface {

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
        description = "How frequently to poll the Pennylane changelog for new supplier invoices. ISO-8601 duration. Defaults to PT5M."
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
            "supplier_invoices",
            previous,
            lookback
        );

        List<SupplierInvoice> invoices = new ArrayList<>();
        for (Changelog change : sync.unseen()) {
            if (change.deleted() || change.getId() == null) {
                continue;
            }
            SupplierInvoice invoice = AbstractPennylaneTask.fetchById(
                runContext,
                this.options,
                rApiToken,
                rBaseUrl,
                "supplier_invoices/" + change.getId(),
                SupplierInvoice.class
            );
            if (invoice != null) {
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
                "Pennylane SupplierInvoiceTrigger fired: {} supplier invoice(s) from {} changelog event(s)",
                invoices.size(),
                sync.unseen().size()
            );
        }

        PennylaneWatermark.save(runContext, namespace, watermarkKey, sync.next());
        return execution;
    }
}
