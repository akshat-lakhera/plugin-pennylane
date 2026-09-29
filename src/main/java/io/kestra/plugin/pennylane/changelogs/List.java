package io.kestra.plugin.pennylane.changelogs;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.models.tasks.common.FetchType;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.pennylane.AbstractPennylaneTask;
import io.kestra.plugin.pennylane.models.Changelog;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "List Pennylane changelog events",
    description = "Returns incremental change events for a Pennylane resource type. " +
        "The changelog endpoint retains events for the last 4 weeks. " +
        "Pass `since` to only retrieve changes after a given timestamp — ideal for watermark-based incremental ingestion."
)
@Plugin(
    examples = {
        @Example(
            title = "List recent supplier invoice changes",
            full = true,
            code = """
                id: pennylane_changelogs_supplier_invoices
                namespace: company.finance

                tasks:
                  - id: changelogs
                    type: io.kestra.plugin.pennylane.changelogs.List
                    apiToken: "{{ secret('PENNYLANE_API_TOKEN') }}"
                    resource: supplier_invoices
                    since: "2024-01-01T00:00:00Z"
                    fetchType: FETCH
                """
        )
    }
)
public class List extends AbstractPennylaneTask implements RunnableTask<List.Output> {

    /**
     * Supported resource names for the changelogs endpoint.
     */
    public enum ChangelogResource {
        supplier_invoices,
        customer_invoices,
        transactions,
        ledger_entry_lines,
        customers,
        suppliers
    }

    @Schema(
        title = "Resource type",
        description = "Pennylane resource type to retrieve changelog events for. " +
            "Supported values: supplier_invoices, customer_invoices, transactions, " +
            "ledger_entry_lines, customers, suppliers."
    )
    @PluginProperty(group = "processing")
    private Property<ChangelogResource> resource;

    @Schema(
        title = "Since timestamp",
        description = "ISO-8601 timestamp. Only change events that occurred after this timestamp are returned. " +
            "If omitted, the API returns the oldest retained set of changes (up to 4 weeks ago)."
    )
    @PluginProperty(group = "processing")
    private Property<String> since;

    @Schema(
        title = "Page size",
        description = "Number of items per request page (1 to 100). Defaults to 100."
    )
    @Builder.Default
    @PluginProperty(group = "processing")
    private Property<Integer> pageSize = Property.ofValue(100);

    @Schema(
        title = "Maximum records",
        description = "Maximum total number of change events to retrieve across all pages. Omit to fetch all."
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
        ChangelogResource resourceType = runContext.render(this.resource)
            .as(ChangelogResource.class)
            .orElseThrow(() -> new IllegalArgumentException("resource is required for changelogs.List"));

        Map<String, String> queryParams = new LinkedHashMap<>();

        int limit = runContext.render(this.pageSize).as(Integer.class).orElse(100);
        queryParams.put("limit", String.valueOf(Math.min(100, Math.max(1, limit))));

        if (this.since != null) {
            runContext.render(this.since).as(String.class).ifPresent(s ->
                queryParams.put("start_date", s)
            );
        }

        Integer max = runContext.render(this.maxRecords).as(Integer.class).orElse(null);

        // Changelog path: GET /changelogs/{resource}
        String endpointPath = "changelogs/" + resourceType.name();

        java.util.List<Changelog> items = paginate(
            runContext,
            endpointPath,
            queryParams,
            Changelog.class,
            max
        );

        FetchResult<Changelog> result = fetchOutput(runContext, this.fetchType, items);

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
        @Schema(title = "List of changelog events (populated when fetchType is FETCH)")
        private final java.util.List<Changelog> rows;

        @Schema(title = "First changelog event (populated when fetchType is FETCH_ONE)")
        private final Changelog row;

        @Schema(title = "URI of the stored .ion internal storage file (populated when fetchType is STORE)")
        private final URI uri;

        @Schema(title = "Total number of changelog events retrieved")
        private final Integer count;
    }
}
