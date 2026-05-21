package com.history.backend.integration.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ConnectSlackIntegrationRequest(
        @JsonProperty("token")
        @NotBlank
        @Size(max = 4096)
        String token
) {
}
