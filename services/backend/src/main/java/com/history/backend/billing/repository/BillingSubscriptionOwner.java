package com.history.backend.billing.repository;

import java.util.UUID;

// BillingSubscriptionRepository.findOwnerBySubscriptionId의 스칼라 projection
public interface BillingSubscriptionOwner {

    UUID getUserId();

    String getPriceId();
}
