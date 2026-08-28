package com.history.backend.auth.dto;

import jakarta.validation.constraints.NotBlank;

public record UpgradePlanRequest(@NotBlank String code) {
}
