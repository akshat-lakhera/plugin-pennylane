package io.kestra.plugin.pennylane;

import io.kestra.core.http.client.configurations.HttpConfiguration;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.triggers.AbstractTrigger;
import io.kestra.core.models.triggers.PollingTriggerInterface;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

import java.time.Duration;

/**
 * Shared authentication, HTTP transport configuration, and polling interval for Pennylane triggers.
 */
@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
public abstract class AbstractPennylaneTrigger extends AbstractTrigger implements PollingTriggerInterface {

    @Schema(
        title = "Pennylane API token",
        description = "Company or firm API token used to authenticate against the Pennylane API. Store it securely as a Kestra secret."
    )
    @NotNull
    @PluginProperty(secret = true, group = "connection")
    @ToString.Exclude
    protected Property<String> apiToken;

    @Schema(
        title = "Pennylane API base URL",
        description = "Base endpoint URL for Pennylane API calls. Defaults to `" + AbstractPennylaneTask.DEFAULT_BASE_URL + "`."
    )
    @Builder.Default
    @PluginProperty(group = "connection")
    protected Property<String> baseUrl = Property.ofValue(AbstractPennylaneTask.DEFAULT_BASE_URL);

    @Schema(
        title = "HTTP client options",
        description = "Optional HTTP client configuration (timeouts, proxy, SSL) applied to every request."
    )
    @PluginProperty(group = "advanced")
    protected HttpConfiguration options;

    @Schema(
        title = "Polling interval",
        description = "How frequently to poll the Pennylane changelog. ISO-8601 duration. Defaults to PT5M (PT10M for CustomerInvoicePaidTrigger)."
    )
    @PluginProperty(group = "advanced")
    protected Duration interval;

    protected Duration defaultInterval() {
        return Duration.ofMinutes(5);
    }

    @Override
    public Duration getInterval() {
        return this.interval != null ? this.interval : defaultInterval();
    }
}
