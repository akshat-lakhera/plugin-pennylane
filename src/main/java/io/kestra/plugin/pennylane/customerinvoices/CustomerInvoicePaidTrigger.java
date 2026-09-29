package io.kestra.plugin.pennylane.customerinvoices;

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
import io.kestra.plugin.pennylane.models.CustomerInvoice;
import io.kestra.plugin.pennylane.models.PennylanePage;
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
    title = "Trigger when a Pennylane customer invoice is paid",
    description = "Polls the Pennylane changelog for updated customer invoices at a configurable interval. " +
        "Fires one execution per poll cycle when customer invoices with paid status are detected. " +
        "The most recent paid invoice is available as `trigger.invoice` in downstream tasks."
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

        String token = AbstractPennylaneTask.renderApiToken(runContext, this.apiToken);
        String baseUrlStr = AbstractPennylaneTask.renderBaseUrl(runContext, this.baseUrl);

        // Compute since timestamp: last evaluation date minus one interval
        String since = context.getDate()
            .minus(this.interval)
            .withZoneSameInstant(ZoneOffset.UTC)
            .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);

        // Poll the customer invoices changelog for changes since last run
        Map<String, String> changelogParams = new LinkedHashMap<>();
        changelogParams.put("start_date", since);
        changelogParams.put("limit", "100");

        String changelogUrl = AbstractPennylaneTask.buildUriWithParams(
            baseUrlStr,
            "changelogs/customer_invoices",
            changelogParams
        );

        var changelogRequest = HttpRequest.builder()
            .uri(URI.create(changelogUrl))
            .method("GET");

        var pageType = AbstractPennylaneTask.MAPPER
            .getTypeFactory()
            .constructParametricType(PennylanePage.class, Changelog.class);

        @SuppressWarnings("unchecked")
        PennylanePage<Changelog> page = (PennylanePage<Changelog>) AbstractPennylaneTask.request(
            runContext,
            null,
            token,
            changelogRequest,
            pageType
        ).getBody();

        List<Changelog> changes = page != null && page.getItems() != null ? page.getItems() : List.of();

        if (changes.isEmpty()) {
            return Optional.empty();
        }

        // Fetch the full invoice for the most recent changelog entry and check paid status
        CustomerInvoice paidInvoice = null;
        for (Changelog change : changes) {
            Long invoiceId = change.getResourceId();
            if (invoiceId == null) {
                continue;
            }

            String invoiceUrl = AbstractPennylaneTask.join(baseUrlStr, "customer_invoices/" + invoiceId);
            var invoiceRequest = HttpRequest.builder()
                .uri(URI.create(invoiceUrl))
                .method("GET");

            CustomerInvoice invoice = AbstractPennylaneTask.request(
                runContext,
                null,
                token,
                invoiceRequest,
                CustomerInvoice.class
            ).getBody();

            if (invoice != null && Boolean.TRUE.equals(invoice.getPaid())) {
                paidInvoice = invoice;
                break;
            }
        }

        if (paidInvoice == null) {
            return Optional.empty();
        }

        Map<String, Object> outputs = new LinkedHashMap<>();
        outputs.put("invoice", paidInvoice);

        Execution execution = TriggerService.generateExecution(this, conditionContext, context, outputs);

        runContext.logger().info(
            "Pennylane CustomerInvoicePaidTrigger fired: paid invoice {} detected",
            paidInvoice.getId()
        );

        return Optional.of(execution);
    }
}
