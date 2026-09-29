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
public class LedgerEntry {

    @Schema(title = "Ledger entry unique identifier")
    @JsonProperty("id")
    private Long id;

    @Schema(title = "Entry label")
    @JsonProperty("label")
    private String label;

    @Schema(title = "Accounting date (YYYY-MM-DD)")
    @JsonProperty("date")
    private String date;

    @Schema(title = "Journal identifier")
    @JsonProperty("journal_id")
    private Long journalId;

    @Schema(title = "Attached ledger document filename")
    @JsonProperty("ledger_attachment_filename")
    private String ledgerAttachmentFilename;

    @Schema(title = "Creation timestamp")
    @JsonProperty("created_at")
    private String createdAt;

    @Schema(title = "Last update timestamp")
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
