package io.kestra.plugin.pennylane.masterdata.customers;

import com.fasterxml.jackson.core.type.TypeReference;
import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.models.tasks.common.FetchType;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.pennylane.AbstractPennylaneTask;
import io.kestra.plugin.pennylane.models.Customer;
import io.kestra.plugin.pennylane.models.PennylaneFilter;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "List Pennylane customers",
    description = "Retrieves customers from Pennylane with cursor pagination and optional filtering."
)
@Plugin(
    examples = {
        @Example(
            title = "List customers in Pennylane",
            full = true,
            code = """
                id: pennylane_customers
                namespace: company.finance

                tasks:
                  - id: customers
                    type: io.kestra.plugin.pennylane.masterdata.customers.List
                    apiToken: "{{ secret('PENNYLANE_API_TOKEN') }}"
                    fetchType: FETCH
                """
        )
    }
)
public class List extends AbstractPennylaneTask implements RunnableTask<List.Output> {

    @Schema(
        title = "Name search",
        description = "Filters customers whose name starts with this value. Sent as name with operator start_with."
    )
    @PluginProperty(group = "processing")
    private Property<String> search;

    @Schema(
        title = "Raw Pennylane filter DSL",
        description = "Raw JSON filter string matching Pennylane filter DSL. Name search uses operator start_with."
    )
    @PluginProperty(group = "processing")
    private Property<String> filter;

    @Schema(
        title = "Sort order",
        description = "Attribute to sort by. Defaults to '-id'."
    )
    @Builder.Default
    @PluginProperty(group = "processing")
    private Property<String> sort = Property.ofValue("-id");

    @Schema(
        title = "Page size",
        description = "Number of items per request page. Must be between 1 and 100. Defaults to 100.",
        minimum = "1",
        maximum = "100"
    )
    @Builder.Default
    @PluginProperty(group = "processing")
    private Property<Integer> pageSize = Property.ofValue(100);

    @Schema(
        title = "Maximum records",
        description = "Maximum total number of records to retrieve across all pages. Omit to fetch all matching records. Must be at least 1 when set.",
        minimum = "1"
    )
    @PluginProperty(group = "processing")
    private Property<Integer> maxRecords;

    @Schema(
        title = "Fetch type",
        description = "Defines how results are emitted: FETCH, FETCH_ONE, STORE, or NONE. Defaults to FETCH."
    )
    @Builder.Default
    @PluginProperty(group = "processing")
    private Property<FetchType> fetchType = Property.ofValue(FetchType.FETCH);

    @Override
    public Output run(RunContext runContext) throws Exception {
        Map<String, String> queryParams = new LinkedHashMap<>();

        int rPageSize = renderPageSize(runContext, this.pageSize, MAX_LIST_PAGE_SIZE);
        queryParams.put("limit", String.valueOf(rPageSize));

        String rSort = runContext.render(this.sort).as(String.class).orElse("-id");
        queryParams.put("sort", rSort);

        java.util.List<PennylaneFilter> filterList = new ArrayList<>();

        if (this.search != null) {
            runContext.render(this.search).as(String.class).ifPresent(name -> {
                if (!name.isBlank()) {
                    filterList.add(PennylaneFilter.builder().field("name").operator("start_with").value(name).build());
                }
            });
        }

        if (this.filter != null) {
            String rawFilter = runContext.render(this.filter).as(String.class).orElse(null);
            if (rawFilter != null && !rawFilter.isBlank()) {
                java.util.List<PennylaneFilter> parsed = MAPPER.readValue(
                    rawFilter,
                    new TypeReference<java.util.List<PennylaneFilter>>() {}
                );
                filterList.addAll(parsed);
            }
        }

        if (!filterList.isEmpty()) {
            queryParams.put("filter", MAPPER.writeValueAsString(filterList));
        }

        Integer rMaxRecords = renderMaxRecords(runContext, this.maxRecords);
        FetchResult<Customer> result = drain(
            runContext,
            "customers",
            queryParams,
            Customer.class,
            this.fetchType,
            rMaxRecords,
            PageMode.STANDARD,
            null
        );

        return Output.builder()
            .rows(result.rows())
            .row(result.row())
            .uri(result.uri())
            .count(result.count())
            .build();
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(title = "List of customers (populated when fetchType is FETCH)")
        private final java.util.List<Customer> rows;

        @Schema(title = "First customer (populated when fetchType is FETCH_ONE)")
        private final Customer row;

        @Schema(title = "URI of the stored .ion internal storage file (populated when fetchType is STORE)")
        private final URI uri;

        @Schema(title = "Total number of customers retrieved")
        private final Integer count;
    }
}
