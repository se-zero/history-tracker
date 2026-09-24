package com.history.backend.billing.repository;

import java.util.List;
import java.util.UUID;

import com.history.backend.billing.domain.BillingSubscription;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BillingSubscriptionRepository extends JpaRepository<BillingSubscription, String> {

    // canceled·paused 알림을 받았을 때, 같은 사용자의 다른 살아 있는 구독이 있으면 강등하지 않기 위한 검사
    boolean existsByUserIdAndStatusInAndSubscriptionIdNot(UUID userId, List<String> statuses, String subscriptionId);
}
