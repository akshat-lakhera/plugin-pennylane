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
public class Transaction {

    @Schema(title = "Bank transaction unique identifier.")
    @JsonProperty("id")
    private Long id;

    @Schema(title = "Bank transaction description or label.")
    @JsonProperty("label")
    private String label;

    @Schema(title = "Transaction amount in euros.")
    @JsonProperty("amount")
    private Object amount;

    @Schema(title = "Currency code.")
    @JsonProperty("currency")
    private String currency;

    @Schema(title = "Transaction amount in original account currency.")
    @JsonProperty("currency_amount")
    private Object currencyAmount;

    @Schema(title = "Transaction execution date (YYYY-MM-DD).")
    @JsonProperty("date")
    private String date;

    @Schema(title = "Transaction value / settlement date (YYYY-MM-DD).")
    @JsonProperty("settlement_date")
    private String settlementDate;

    @Schema(title = "Associated bank account identifier.")
    @JsonProperty("bank_account_id")
    private Long bankAccountId;

    @Schema(title = "Associated journal identifier if matched.")
    @JsonProperty("journal_id")
    private Long journalId;

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
