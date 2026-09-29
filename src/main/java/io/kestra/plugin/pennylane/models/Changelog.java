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
public class Changelog {

    @Schema(title = "Changelog event unique identifier")
    @JsonProperty("id")
    private Long id;

    @Schema(title = "Action performed on the resource: 'create', 'update', or 'delete'")
    @JsonProperty("action")
    private String action;

    @Schema(title = "Identifier of the affected resource")
    @JsonProperty("resource_id")
    private Long resourceId;

    @Schema(title = "Timestamp of the change event")
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
