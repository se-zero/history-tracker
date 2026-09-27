package com.history.backend.billing.dto;

public record CheckoutSessionResponse(
        String transactionId,
        String clientToken,
        String environment
) {
}
