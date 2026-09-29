package io.kestra.plugin.pennylane.masterdata.suppliers;

import io.kestra.core.http.HttpRequest;
import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.pennylane.AbstractPennylaneTask;
import io.kestra.plugin.pennylane.models.Supplier;
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
    title = "Get a Pennylane supplier",
    description = "Retrieves a single supplier by ID."
)
@Plugin(
    examples = {
        @Example(
            title = "Get a supplier by ID",
            full = true,
            code = """
                id: get_pennylane_supplier
                namespace: company.finance

                tasks:
                  - id: get_sup
                    type: io.kestra.plugin.pennylane.masterdata.suppliers.Get
                    apiToken: "{{ secret('PENNYLANE_API_TOKEN') }}"
                    supplierId: 7788
                """
        )
    }
)
public class Get extends AbstractPennylaneTask implements RunnableTask<Get.Output> {

    @Schema(
        title = "Supplier ID",
        description = "Unique identifier of the supplier to retrieve."
    )
    @NotNull
    @PluginProperty(group = "main")
    private Property<Long> supplierId;

    @Override
    public Output run(RunContext runContext) throws Exception {
        Long idVal = runContext.render(this.supplierId).as(Long.class).orElseThrow(
            () -> new IllegalArgumentException("supplierId is required")
        );

        String baseUrlStr = renderBaseUrl(runContext);
        String url = join(baseUrlStr, "suppliers/" + idVal);

        var requestBuilder = HttpRequest.builder()
            .uri(URI.create(url))
            .method("GET");

        Supplier supplier = request(runContext, requestBuilder, Supplier.class).getBody();

        return Output.builder()
            .row(supplier)
            .build();
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(title = "The retrieved supplier")
        private final Supplier row;
    }
}
