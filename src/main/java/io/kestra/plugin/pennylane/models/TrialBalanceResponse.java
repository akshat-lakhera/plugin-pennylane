package io.kestra.plugin.pennylane.models;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Top-level response from GET /trial_balance.
 * Not paginated — the API returns the full balance in one response.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class TrialBalanceResponse {

    @Schema(title = "List of ledger account trial balance rows for the requested period")
    @JsonProperty("ledger_accounts")
    @JsonAlias({"items", "ledger_accounts"})
    private List<TrialBalanceLine> ledgerAccounts;
}
