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
public class SupplierInvoice {

    @Schema(title = "Supplier invoice unique identifier")
    @JsonProperty("id")
    private Long id;

    @Schema(title = "Supplier invoice display label")
    @JsonProperty("label")
    private String label;

    @Schema(title = "Supplier invoice number as issued by the vendor")
    @JsonProperty("invoice_number")
    private String invoiceNumber;

    @Schema(title = "Currency code (e.g. 'EUR', 'USD')")
    @JsonProperty("currency")
    private String currency;

    @Schema(title = "Invoice issue date (YYYY-MM-DD)")
    @JsonProperty("date")
    private String date;

    @Schema(title = "Invoice payment deadline (YYYY-MM-DD)")
    @JsonProperty("deadline")
    private String deadline;

    @Schema(title = "Total invoice amount including tax")
    @JsonProperty("amount")
    private Object amount;

    @Schema(title = "Invoice amount in original currency")
    @JsonProperty("currency_amount")
    private Object currencyAmount;

    @Schema(title = "Tax / VAT amount in euros")
    @JsonProperty("tax")
    private Object tax;

    @Schema(title = "Tax / VAT amount in invoice currency")
    @JsonProperty("currency_tax")
    private Object currencyTax;

    @Schema(title = "Payment status, e.g. 'paid', 'not_paid', 'partially_paid'")
    @JsonProperty("payment_status")
    private String paymentStatus;

    @Schema(title = "Accounting validation state ('draft', 'entry', 'validation_needed', 'complete', 'archived')")
    @JsonProperty("accounting_status")
    private String accountingStatus;

    @Schema(title = "Whether the invoice has been bank reconciled")
    @JsonProperty("reconciled")
    private Boolean reconciled;

    @Schema(title = "Name of the attached invoice document file")
    @JsonProperty("filename")
    private String filename;

    @Schema(title = "Public temporary download URL for the invoice PDF (expires in ~30 min)")
    @JsonProperty("public_file_url")
    private String publicFileUrl;

    @Schema(title = "Source electronic invoice file URL if available")
    @JsonProperty("source_file_url")
    private String sourceFileUrl;

    @Schema(title = "Supplier summary reference")
    @JsonProperty("supplier")
    private Map<String, Object> supplier;

    @Schema(title = "Timestamp when the invoice was created in Pennylane")
    @JsonProperty("created_at")
    private String createdAt;

    @Schema(title = "Timestamp when the invoice was last updated in Pennylane")
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
