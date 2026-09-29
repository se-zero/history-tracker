package com.history.backend.billing.dto;

import java.time.Instant;

public record BillingSummaryResponse(
        PlanSource planSource,
        SubscriptionView subscription,
        Instant lastSubscriptionEndedAt,
        boolean checkoutAvailable
) {
    public enum PlanSource {
        SUBSCRIPTION,
        CODE,
        NONE
    }

    public record SubscriptionView(
            String status,
            Instant currentPeriodEndsAt,
            Instant cancelScheduledAt
    ) {
    }
}
