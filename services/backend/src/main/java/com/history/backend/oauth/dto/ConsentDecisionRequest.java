package com.history.backend.oauth.dto;

import jakarta.validation.constraints.NotNull;

public record ConsentDecisionRequest(
        @NotNull String query,
        @NotNull Boolean approved
) {
}
