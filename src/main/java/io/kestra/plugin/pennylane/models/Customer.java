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
import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class Customer {

    @Schema(title = "Customer unique identifier")
    @JsonProperty("id")
    private Long id;

    @Schema(title = "Customer name")
    @JsonProperty("name")
    private String name;

    @Schema(title = "Customer type ('company' or 'individual')")
    @JsonProperty("customer_type")
    private String customerType;

    @Schema(title = "List of customer email addresses")
    @JsonProperty("emails")
    private List<String> emails;

    @Schema(title = "List of customer phone numbers")
    @JsonProperty("phone_numbers")
    private List<String> phoneNumbers;

    @Schema(title = "Address line")
    @JsonProperty("address")
    private String address;

    @Schema(title = "Postal code")
    @JsonProperty("postal_code")
    private String postalCode;

    @Schema(title = "City name")
    @JsonProperty("city")
    private String city;

    @Schema(title = "Two-letter ISO country code")
    @JsonProperty("country_alpha2")
    private String countryAlpha2;

    @Schema(title = "VAT identification number")
    @JsonProperty("vat_number")
    private String vatNumber;

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
