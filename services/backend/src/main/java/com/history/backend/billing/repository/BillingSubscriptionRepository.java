package com.history.backend.billing.repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.history.backend.billing.domain.BillingSubscription;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

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

    // 동기화가 "캐시에는 살아 있는데 Paddle 목록에 없는" 구독 후보를 고르는 조회. 비교와 쓰기는 applier가
    // 사용자를 잠근 뒤 직접 읽어 하므로 여기서는 ID만 읽는다 — 엔티티를 읽으면 잠금 전의 옛 사본이 생긴다.
    @Query("select s.subscriptionId from BillingSubscription s where s.status in :statuses")
    List<String> findSubscriptionIdsByStatusIn(@Param("statuses") Collection<String> statuses);

    // 결제일 안내 표시 단독 갱신. 분리된 엔티티를 통째로 save하면 같은 시각 웹훅·동기화가 쓴 다른 칸을
    // 옛 값으로 되돌린다. 호출자(RenewalNoticeService)에 트랜잭션이 없어서 여기서 직접 연다.
    // 엔티티의 renewalNoticePeriodEnd는 updatable=false라 JPQL이 아닌 native로 써야 이 칸을 고칠 수 있다.
    @Modifying
    @Transactional
    @Query(value = "UPDATE billing_subscriptions SET renewal_notice_period_end = :periodEnd"
            + " WHERE subscription_id = :subscriptionId", nativeQuery = true)
    void markRenewalNoticeSent(
            @Param("subscriptionId") String subscriptionId,
            @Param("periodEnd") Instant periodEnd
    );

    List<BillingSubscription> findByCurrentPeriodEndsAtGreaterThanEqualAndCurrentPeriodEndsAtLessThan(
            Instant windowStart,
            Instant windowEnd
    );
}
