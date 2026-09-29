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
public class LedgerEntryLine {

    @Schema(title = "Ledger entry line unique identifier.")
    @JsonProperty("id")
    private Long id;

    @Schema(title = "Associated ledger entry identifier.")
    @JsonProperty("ledger_entry_id")
    private Long ledgerEntryId;

    @Schema(title = "Associated ledger account identifier.")
    @JsonProperty("ledger_account_id")
    private Long ledgerAccountId;

    @Schema(title = "Debit amount in euros.")
    @JsonProperty("debit")
    private Object debit;

    @Schema(title = "Credit amount in euros.")
    @JsonProperty("credit")
    private Object credit;

    @Schema(title = "Currency code.")
    @JsonProperty("currency")
    private String currency;

    @Schema(title = "Debit amount in document currency.")
    @JsonProperty("currency_debit")
    private Object currencyDebit;

    @Schema(title = "Credit amount in document currency.")
    @JsonProperty("currency_credit")
    private Object currencyCredit;

    @Schema(title = "Entry date (YYYY-MM-DD).")
    @JsonProperty("date")
    private String date;

    @Schema(title = "Label / memo for this line.")
    @JsonProperty("label")
    private String label;

    @Schema(title = "Lettering code used for account reconciliation.")
    @JsonProperty("lettering")
    private String lettering;

    @Schema(title = "Creation timestamp in Pennylane.")
    @JsonProperty("created_at")
    private String createdAt;

    @Schema(title = "Last update timestamp in Pennylane.")
    @JsonProperty("updated_at")
    private String updatedAt;

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
