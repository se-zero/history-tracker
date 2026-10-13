package com.history.backend.billing.dto;

import java.time.Instant;

public record CheckoutAvailabilityResponse(boolean checkoutAvailable, Instant betaEndsAt) {
}
