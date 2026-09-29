package io.kestra.plugin.pennylane.supplierinvoices;

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
import io.kestra.plugin.pennylane.models.Changelog;
import io.kestra.plugin.pennylane.models.PennylanePage;
import io.kestra.plugin.pennylane.models.SupplierInvoice;
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
    description = "Polls the Pennylane changelog for new or updated supplier invoices at a configurable interval. " +
        "Fires one execution per poll cycle when new supplier invoices are detected. " +
        "The most recent new invoice is available as `trigger.invoice` in downstream tasks."
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

        String token = AbstractPennylaneTask.renderApiToken(runContext, this.apiToken);
        String baseUrlStr = AbstractPennylaneTask.renderBaseUrl(runContext, this.baseUrl);

        // Compute since timestamp: last evaluation date (current scheduled date minus one interval)
        String since = context.getDate()
            .minus(this.interval)
            .withZoneSameInstant(ZoneOffset.UTC)
            .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);

        Map<String, String> queryParams = new LinkedHashMap<>();
        queryParams.put("start_date", since);
        queryParams.put("limit", "100");

        String url = AbstractPennylaneTask.buildUriWithParams(baseUrlStr, "changelogs/supplier_invoices", queryParams);

        var requestBuilder = HttpRequest.builder()
            .uri(URI.create(url))
            .method("GET");

        var pageType = AbstractPennylaneTask.MAPPER
            .getTypeFactory()
            .constructParametricType(PennylanePage.class, Changelog.class);

        @SuppressWarnings("unchecked")
        PennylanePage<Changelog> page = (PennylanePage<Changelog>) AbstractPennylaneTask.request(
            runContext,
            null,
            token,
            requestBuilder,
            pageType
        ).getBody();

        List<Changelog> changes = page != null && page.getItems() != null ? page.getItems() : List.of();

        if (changes.isEmpty()) {
            return Optional.empty();
        }

        // Fetch the full invoice for the most recent changelog entry
        Changelog latest = changes.get(0);
        Long invoiceId = latest.getResourceId();

        SupplierInvoice invoice;
        if (invoiceId != null) {
            String invoiceUrl = AbstractPennylaneTask.join(baseUrlStr, "supplier_invoices/" + invoiceId);
            var invoiceRequest = HttpRequest.builder()
                .uri(URI.create(invoiceUrl))
                .method("GET");

            invoice = AbstractPennylaneTask.request(
                runContext,
                null,
                token,
                invoiceRequest,
                SupplierInvoice.class
            ).getBody();
        } else {
            invoice = null;
        }

        Map<String, Object> outputs = new LinkedHashMap<>();
        outputs.put("invoice", invoice);
        outputs.put("changeCount", changes.size());

        Execution execution = TriggerService.generateExecution(this, conditionContext, context, outputs);

        runContext.logger().info(
            "Pennylane SupplierInvoiceTrigger fired: {} new/updated supplier invoice(s) since {}",
            changes.size(),
            since
        );

        return Optional.of(execution);
    }
}
