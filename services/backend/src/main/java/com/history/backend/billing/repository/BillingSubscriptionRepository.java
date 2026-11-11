package com.history.backend.billing.repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.history.backend.billing.domain.BillingSubscription;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BillingSubscriptionRepository extends JpaRepository<BillingSubscription, String> {

    // 사용자 잠금 전에 쓰는 소유자·가격 조회. 엔티티가 아니라 스칼라 projection으로 읽어야 영속성
    // 컨텍스트에 사본이 남지 않는다 — 남으면 잠근 뒤 findById가 DB가 아니라 이 옛 사본을 돌려준다.
    @Query("select s.userId as userId, s.priceId as priceId from BillingSubscription s"
            + " where s.subscriptionId = :subscriptionId")
    Optional<BillingSubscriptionOwner> findOwnerBySubscriptionId(@Param("subscriptionId") String subscriptionId);

    // canceled·paused 알림을 받았을 때, 같은 사용자의 다른 살아 있는 구독이 있으면 강등하지 않기 위한 검사
    boolean existsByUserIdAndStatusInAndSubscriptionIdNot(UUID userId, List<String> statuses, String subscriptionId);

    List<BillingSubscription> findByUserIdAndStatusIn(UUID userId, Collection<String> statuses);

    Optional<BillingSubscription> findFirstByUserIdAndStatusOrderByCanceledAtDesc(UUID userId, String status);

    List<BillingSubscription> findByCurrentPeriodEndsAtGreaterThanEqualAndCurrentPeriodEndsAtLessThan(
            Instant windowStart,
            Instant windowEnd
    );
}
