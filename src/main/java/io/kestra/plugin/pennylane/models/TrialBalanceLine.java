package io.kestra.plugin.pennylane.models;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.HashMap;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class TrialBalanceLine {

    @Schema(title = "Ledger account number")
    @JsonProperty("number")
    private String number;

    @Schema(title = "Formatted ledger account number")
    @JsonProperty("formatted_number")
    private String formattedNumber;

    @Schema(title = "Ledger account label")
    @JsonProperty("label")
    private String label;

    @Schema(title = "Total debits for the period")
    @JsonProperty("debits")
    private Object debits;

    @Schema(title = "Total credits for the period")
    @JsonProperty("credits")
    private Object credits;

    @Builder.Default
    private Map<String, Object> additionalProperties = new HashMap<>();

    @JsonAnyGetter
    public Map<String, Object> getAdditionalProperties() {
        return this.additionalProperties;
    }

    @JsonAnySetter
    public void setAdditionalProperty(String name, Object value) {
        if (this.additionalProperties == null) {
            this.additionalProperties = new HashMap<>();
        }
        this.additionalProperties.put(name, value);
    }
}
