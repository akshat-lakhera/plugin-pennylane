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

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class Transaction {

    @Schema(title = "Bank transaction unique identifier")
    @JsonProperty("id")
    private Long id;

    @Schema(title = "Bank transaction description or label")
    @JsonProperty("label")
    private String label;

    @Schema(title = "Transaction amount in euros")
    @JsonProperty("amount")
    private Object amount;

    @Schema(title = "Currency code")
    @JsonProperty("currency")
    private String currency;

    @Schema(title = "Transaction amount in original account currency")
    @JsonProperty("currency_amount")
    private Object currencyAmount;

    @Schema(title = "Transaction execution date (YYYY-MM-DD)")
    @JsonProperty("date")
    private String date;

    @Schema(title = "Transaction value / settlement date (YYYY-MM-DD)")
    @JsonProperty("settlement_date")
    private String settlementDate;

    @Schema(title = "Associated bank account identifier, when the API sends it as a scalar")
    @JsonProperty("bank_account_id")
    private Long bankAccountId;

    @Schema(title = "Associated bank account object ({id, url})")
    @JsonProperty("bank_account")
    private Map<String, Object> bankAccount;

    @Schema(title = "Associated journal identifier if matched")
    @JsonProperty("journal_id")
    private Long journalId;

    @Schema(title = "Whether this transaction is categorized. Present on some payloads; otherwise derived from categories.")
    @JsonProperty("categorized")
    private Boolean categorized;

    @Schema(title = "Categories attached to the transaction")
    @JsonProperty("categories")
    private List<Map<String, Object>> categories;

    @Schema(title = "Category identifier if the transaction has been categorized")
    @JsonProperty("category_id")
    private Long categoryId;

    @Schema(title = "Creation timestamp in Pennylane")
    @JsonProperty("created_at")
    private String createdAt;

    @Schema(title = "Last update timestamp in Pennylane")
    @JsonProperty("updated_at")
    private String updatedAt;

    public Long resolvedBankAccountId() {
        if (bankAccountId != null) {
            return bankAccountId;
        }
        if (bankAccount == null) {
            return null;
        }
        Object id = bankAccount.get("id");
        if (id instanceof Number number) {
            return number.longValue();
        }
        if (id instanceof String text) {
            try {
                return Long.parseLong(text);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    public boolean isCategorizedForFilter() {
        if (categorized != null) {
            return categorized;
        }
        return categories != null && !categories.isEmpty();
    }

    public BigDecimal decimalAmount() {
        if (amount == null) {
            return null;
        }
        try {
            return new BigDecimal(amount.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

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
