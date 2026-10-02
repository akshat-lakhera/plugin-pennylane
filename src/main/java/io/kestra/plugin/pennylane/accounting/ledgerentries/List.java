package io.kestra.plugin.pennylane.accounting.ledgerentries;

import com.fasterxml.jackson.core.type.TypeReference;
import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.models.tasks.common.FetchType;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.pennylane.AbstractPennylaneTask;
import io.kestra.plugin.pennylane.models.LedgerEntry;
import io.kestra.plugin.pennylane.models.PennylaneFilter;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
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
    title = "List Pennylane ledger entries",
    description = "Lists ledger entries from GET /ledger_entries. Pennylane API v2 has no journal_entries resource; ledger entries are the replacement. " +
        "Pagination is page and per_page, not a cursor. Legal filters include date (lt, lteq, gt, gteq, eq, not_eq) and journal_id (those operators plus in and not_in)."
)
@Plugin(
    examples = {
        @Example(
            title = "List ledger entries for a journal and date range",
            full = true,
            code = """
                id: pennylane_ledger_entries
                namespace: company.finance

                tasks:
                  - id: ledger_entries
                    type: io.kestra.plugin.pennylane.accounting.ledgerentries.List
                    apiToken: "{{ secret('PENNYLANE_API_TOKEN') }}"
                    journalId: 12
                    dateFrom: "2026-01-01"
                    dateTo: "2026-03-31"
                    fetchType: STORE
                """
        )
    }
)
public class List extends AbstractPennylaneTask implements RunnableTask<List.Output> {

    @Schema(
        title = "Journal identifier",
        description = "Filters ledger entries for this journal. Sent as journal_id eq."
    )
    @PluginProperty(group = "processing")
    private Property<Long> journalId;

    @Schema(
        title = "Entry start date",
        description = "Filters ledger entries on or after this date (YYYY-MM-DD). Sent as date gteq."
    )
    @PluginProperty(group = "processing")
    private Property<String> dateFrom;

    @Schema(
        title = "Entry end date",
        description = "Filters ledger entries on or before this date (YYYY-MM-DD). Sent as date lteq."
    )
    @PluginProperty(group = "processing")
    private Property<String> dateTo;

    @Schema(
        title = "Raw Pennylane filter DSL",
        description = "Raw JSON filter string. Allowed fields are date and journal_id."
    )
    @PluginProperty(group = "processing")
    private Property<String> filter;

    @Schema(
        title = "Sort order",
        description = "Sort by updated_at, created_at, or date, optionally prefixed with '-' for descending order. Defaults to '-date'."
    )
    @Builder.Default
    @PluginProperty(group = "processing")
    private Property<String> sort = Property.ofValue("-date");

    @Schema(
        title = "Page size",
        description = "Number of ledger entries per page (1 to 100). Defaults to 100. Sent as per_page.",
        minimum = "1",
        maximum = "100"
    )
    @Builder.Default
    @PluginProperty(group = "processing")
    private Property<@Min(1) @Max(100) Integer> pageSize = Property.ofValue(DEFAULT_PAGE_SIZE);

    @Schema(
        title = "Maximum records",
        description = "Maximum total number of ledger entries to retrieve across all pages. Omit to fetch all. Must be at least 1 when set.",
        minimum = "1"
    )
    @PluginProperty(group = "processing")
    private Property<@Min(1) Integer> maxRecords;

    @Schema(
        title = "Fetch type",
        description = "Defines how results are emitted: FETCH, FETCH_ONE, STORE, or NONE. Defaults to FETCH."
    )
    @Builder.Default
    @PluginProperty(group = "processing")
    private Property<FetchType> fetchType = Property.ofValue(FetchType.FETCH);

    @Override
    public Output run(RunContext runContext) throws Exception {
        int rPageSize = renderPageSize(runContext, this.pageSize, MAX_LIST_PAGE_SIZE);
        Integer rMaxRecords = renderMaxRecords(runContext, this.maxRecords);
        String rSort = runContext.render(this.sort).as(String.class).orElse("-date");

        Map<String, String> queryParams = new LinkedHashMap<>();
        queryParams.put("sort", rSort);

        java.util.List<PennylaneFilter> filterList = new ArrayList<>();
        if (this.journalId != null) {
            runContext.render(this.journalId).as(Long.class).ifPresent(id ->
                filterList.add(PennylaneFilter.builder().field("journal_id").operator("eq").value(id).build())
            );
        }
        if (this.dateFrom != null) {
            runContext.render(this.dateFrom).as(String.class).ifPresent(date ->
                filterList.add(PennylaneFilter.builder().field("date").operator("gteq").value(date).build())
            );
        }
        if (this.dateTo != null) {
            runContext.render(this.dateTo).as(String.class).ifPresent(date ->
                filterList.add(PennylaneFilter.builder().field("date").operator("lteq").value(date).build())
            );
        }
        if (this.filter != null) {
            String rawFilter = runContext.render(this.filter).as(String.class).orElse(null);
            if (rawFilter != null && !rawFilter.isBlank()) {
                filterList.addAll(MAPPER.readValue(rawFilter, new TypeReference<java.util.List<PennylaneFilter>>() {}));
            }
        }
        if (!filterList.isEmpty()) {
            queryParams.put("filter", MAPPER.writeValueAsString(filterList));
        }

        FetchResult<LedgerEntry> result = drainOffset(
            runContext,
            "ledger_entries",
            queryParams,
            LedgerEntry.class,
            this.fetchType,
            rMaxRecords,
            rPageSize
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
        @Schema(title = "List of ledger entries (populated when fetchType is FETCH)")
        private final java.util.List<LedgerEntry> rows;

        @Schema(title = "First ledger entry (populated when fetchType is FETCH_ONE)")
        private final LedgerEntry row;

        @Schema(title = "URI of the stored .ion internal storage file (populated when fetchType is STORE)")
        private final URI uri;

        @Schema(title = "Total number of ledger entries retrieved")
        private final Integer count;
    }
}
