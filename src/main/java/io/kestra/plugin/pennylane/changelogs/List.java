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
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
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
    description = "Returns incremental change events for a Pennylane resource. " +
        "Events are retained for 4 weeks and ordered by processed_at ascending. " +
        "A start_date older than the retention window returns HTTP 422. " +
        "Follow-up pages send only cursor and limit, because start_date together with cursor returns HTTP 400. " +
        "Each item is the resource id, operation (insert, update, or delete), processed_at, updated_at, and created_at."
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
                    since: "{{ now() | dateAdd(-1, 'DAYS') }}"
                    fetchType: FETCH
                """
        )
    }
)
public class List extends AbstractPennylaneTask implements RunnableTask<List.Output> {

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
        description = "Pennylane resource to retrieve changelog events for. " +
            "Supported values: supplier_invoices, customer_invoices, transactions, " +
            "ledger_entry_lines, customers, suppliers."
    )
    @NotNull
    @PluginProperty(group = "processing")
    private Property<ChangelogResource> resource;

    @Schema(
        title = "Since timestamp",
        description = "ISO-8601 timestamp sent as start_date on the first page only. " +
            "The changelog keeps about 4 weeks of events; an older start_date returns HTTP 422."
    )
    @PluginProperty(group = "processing")
    private Property<String> since;

    @Schema(
        title = "Page size",
        description = "Number of changelog events per request. Must be between 1 and 1000. Defaults to 100.",
        minimum = "1",
        maximum = "1000"
    )
    @Builder.Default
    @PluginProperty(group = "processing")
    private Property<@Min(1) @Max(1000) Integer> pageSize = Property.ofValue(DEFAULT_PAGE_SIZE);

    @Schema(
        title = "Maximum records",
        description = "Maximum total number of change events to retrieve across all pages. Omit to fetch all. Must be at least 1 when set. No limit by default: pagination follows the API until the last page. Set `maxRecords` to cap the number of records, especially with `fetchType: FETCH`, which keeps all rows in memory; prefer `STORE` for large exports.",
        minimum = "1"
    )
    @PluginProperty(group = "processing")
    private Property<@Min(1) Integer> maxRecords;

    @Schema(
        title = "Fetch type",
        description = "Defines how results are emitted: FETCH, FETCH_ONE, STORE, or NONE. FETCH_ONE stops at the first matching record and returns it as `row` (`count` is 1, or 0 when nothing matches). Defaults to FETCH."
    )
    @Builder.Default
    @PluginProperty(group = "processing")
    private Property<FetchType> fetchType = Property.ofValue(FetchType.FETCH);

    @Override
    public Output run(RunContext runContext) throws Exception {
        ChangelogResource rResource = runContext.render(this.resource)
            .as(ChangelogResource.class)
            .orElseThrow(() -> new IllegalArgumentException("resource is required for changelogs.List"));

        int rPageSize = renderPageSize(runContext, this.pageSize, MAX_CHANGELOG_PAGE_SIZE);
        Integer rMaxRecords = renderMaxRecords(runContext, this.maxRecords);

        Map<String, String> queryParams = new LinkedHashMap<>();
        queryParams.put("limit", String.valueOf(rPageSize));

        if (this.since != null) {
            runContext.render(this.since).as(String.class).ifPresent(since ->
                queryParams.put("start_date", since)
            );
        }

        FetchResult<Changelog> result = drain(
            runContext,
            "changelogs/" + rResource.name(),
            queryParams,
            Changelog.class,
            this.fetchType,
            rMaxRecords,
            PageMode.CHANGELOG,
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
        @Schema(title = "List of changelog events (populated when fetchType is FETCH)")
        private final java.util.List<Changelog> rows;

        @Schema(title = "First changelog event (populated when fetchType is FETCH_ONE)")
        private final Changelog row;

        @Schema(title = "URI of the stored .ion internal storage file (populated when fetchType is STORE)")
        private final URI uri;

        @Schema(
            title = "Total number of changelog events retrieved",
            description = "For FETCH_ONE, 1 when a row is returned and 0 when nothing matched."
        )
        private final Integer count;
    }
}
