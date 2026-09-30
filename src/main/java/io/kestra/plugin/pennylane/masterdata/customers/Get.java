package io.kestra.plugin.pennylane.masterdata.customers;

import io.kestra.core.http.HttpRequest;
import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.pennylane.AbstractPennylaneTask;
import io.kestra.plugin.pennylane.models.Customer;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

import java.net.URI;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Get a Pennylane customer",
    description = "Retrieves a single customer by ID."
)
@Plugin(
    examples = {
        @Example(
            title = "Get a customer by ID",
            full = true,
            code = """
                id: get_pennylane_customer
                namespace: company.finance

                tasks:
                  - id: get_cust
                    type: io.kestra.plugin.pennylane.masterdata.customers.Get
                    apiToken: "{{ secret('PENNYLANE_API_TOKEN') }}"
                    customerId: 3344
                """
        )
    }
)
public class Get extends AbstractPennylaneTask implements RunnableTask<Get.Output> {

    @Schema(
        title = "Customer ID",
        description = "Unique identifier of the customer to retrieve."
    )
    @NotNull
    @PluginProperty(group = "main")
    private Property<Long> customerId;

    @Override
    public Output run(RunContext runContext) throws Exception {
        Long rCustomerId = runContext.render(this.customerId).as(Long.class).orElseThrow(
            () -> new IllegalArgumentException("customerId is required")
        );

        String rBaseUrl = renderBaseUrl(runContext);
        String url = join(rBaseUrl, "customers/" + rCustomerId);

        var requestBuilder = HttpRequest.builder()
            .uri(URI.create(url))
            .method("GET");

        Customer customer = request(runContext, requestBuilder, Customer.class).getBody();

        return Output.builder()
            .row(customer)
            .build();
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(title = "The retrieved customer")
        private final Customer row;
    }
}
