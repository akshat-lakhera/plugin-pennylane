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

    @Schema(title = "Ledger account unique identifier.")
    @JsonProperty("id")
    private Long id;

    @Schema(title = "Ledger account number.")
    @JsonProperty("number")
    @com.fasterxml.jackson.annotation.JsonAlias({"ledger_account_number", "number"})
    private String number;

    @Schema(title = "Ledger account label.")
    @JsonProperty("label")
    @com.fasterxml.jackson.annotation.JsonAlias({"ledger_account_label", "label"})
    private String label;

    @Schema(title = "Total debit amount for the period.")
    @JsonProperty("debit")
    private Object debit;

    @Schema(title = "Total credit amount for the period.")
    @JsonProperty("credit")
    private Object credit;

    @Schema(title = "Net balance (debit minus credit) for the period.")
    @JsonProperty("balance")
    private Object balance;

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
