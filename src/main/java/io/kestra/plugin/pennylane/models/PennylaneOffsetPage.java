package io.kestra.plugin.pennylane.models;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class PennylaneOffsetPage<T> {

    @Schema(title = "Total number of pages")
    @JsonProperty("total_pages")
    private Integer totalPages;

    @Schema(title = "Current page number, starting at 1")
    @JsonProperty("current_page")
    private Integer currentPage;

    @Schema(title = "Total number of items across all pages")
    @JsonProperty("total_items")
    private Integer totalItems;

    @Schema(title = "Page size requested")
    @JsonProperty("per_page")
    private Integer perPage;

    @Schema(title = "Items on this page")
    @JsonProperty("items")
    @Builder.Default
    private List<T> items = new ArrayList<>();
}
