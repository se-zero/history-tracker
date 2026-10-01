package com.history.backend.billing.dto;

import jakarta.validation.constraints.NotBlank;

public record OpenBillingPortalRequest(@NotBlank String target) {
}
