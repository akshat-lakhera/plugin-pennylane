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
public class CustomerInvoice {

    @Schema(title = "Customer invoice unique identifier.")
    @JsonProperty("id")
    private Long id;

    @Schema(title = "Customer invoice label.")
    @JsonProperty("label")
    private String label;

    @Schema(title = "Customer invoice number.")
    @JsonProperty("invoice_number")
    private String invoiceNumber;

    @Schema(title = "Currency code (e.g. 'EUR', 'USD').")
    @JsonProperty("currency")
    private String currency;

    @Schema(title = "Invoice issue date (YYYY-MM-DD).")
    @JsonProperty("date")
    private String date;

    @Schema(title = "Invoice payment deadline (YYYY-MM-DD).")
    @JsonProperty("deadline")
    private String deadline;

    @Schema(title = "Whether the invoice is in draft state.")
    @JsonProperty("draft")
    private Boolean draft;

    @Schema(title = "Whether this document is a credit note.")
    @JsonProperty("credit_note")
    private Boolean creditNote;

    @Schema(title = "Whether the invoice has been fully paid.")
    @JsonProperty("paid")
    private Boolean paid;

    @Schema(title = "Total invoice amount including tax in euros.")
    @JsonProperty("amount")
    private Object amount;

    @Schema(title = "Total invoice amount in document currency.")
    @JsonProperty("currency_amount")
    private Object currencyAmount;

    @Schema(title = "Tax / VAT amount in euros.")
    @JsonProperty("tax")
    private Object tax;

    @Schema(title = "Tax / VAT amount in document currency.")
    @JsonProperty("currency_tax")
    private Object currencyTax;

    @Schema(title = "Customer reference summary.")
    @JsonProperty("customer")
    private Map<String, Object> customer;

    @Schema(title = "Public temporary download URL for the invoice PDF.")
    @JsonProperty("public_file_url")
    private String publicFileUrl;

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
