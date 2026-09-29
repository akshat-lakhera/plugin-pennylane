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
public class BillingSubscription {

    @Schema(title = "Billing subscription unique identifier")
    @JsonProperty("id")
    private Long id;

    @Schema(title = "Subscription label")
    @JsonProperty("label")
    private String label;

    @Schema(title = "Customer identifier this subscription belongs to")
    @JsonProperty("customer_id")
    private Long customerId;

    @Schema(title = "Billing period start date (YYYY-MM-DD)")
    @JsonProperty("billing_start_date")
    private String billingStartDate;

    @Schema(title = "Billing period end date (YYYY-MM-DD), if applicable")
    @JsonProperty("billing_end_date")
    private String billingEndDate;

    @Schema(title = "Recurring billing frequency")
    @JsonProperty("billing_frequency")
    private String billingFrequency;

    @Schema(title = "Total subscription amount")
    @JsonProperty("amount")
    private Object amount;

    @Schema(title = "Currency code")
    @JsonProperty("currency")
    private String currency;

    @Schema(title = "Whether the subscription is currently active")
    @JsonProperty("active")
    private Boolean active;

    @Schema(title = "Creation timestamp in Pennylane")
    @JsonProperty("created_at")
    private String createdAt;

    @Schema(title = "Last update timestamp in Pennylane")
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
