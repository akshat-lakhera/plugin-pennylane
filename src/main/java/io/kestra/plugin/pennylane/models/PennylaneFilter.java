package io.kestra.plugin.pennylane.models;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class PennylaneFilter {

    @Schema(title = "The field name to filter on, e.g. 'date', 'supplier_id', 'id'")
    @JsonProperty("field")
    private String field;

    @Schema(title = "The comparison operator: 'eq', 'not_eq', 'gt', 'gteq', 'lt', 'lteq', 'in', 'not_in'")
    @JsonProperty("operator")
    private String operator;

    @Schema(title = "The target value or array of values to compare against")
    @JsonProperty("value")
    private Object value;
}
