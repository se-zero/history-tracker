package com.history.backend.billing.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

// 구독 상태 캐시 — Paddle이 진실의 원천이고 이 테이블은 알림을 받을 때마다 최신 상태로 수렴하는
// 캐시다. subscription_id를 PK로 그대로 쓰므로(값을 생성하지 않고 Paddle 값을 그대로 할당) save()가
// merge 경로를 타 upsert처럼 동작한다 — 행이 없으면 insert, 있으면 update.
@Getter
@Entity
@Table(name = "billing_subscriptions")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class BillingSubscription {

    @Id
    @Column(name = "subscription_id")
    private String subscriptionId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "customer_id", nullable = false)
    private String customerId;

    @Column(nullable = false)
    private String status;

    @Column(name = "price_id")
    private String priceId;

    @Column(name = "current_period_ends_at")
    private Instant currentPeriodEndsAt;

    @Column(name = "scheduled_change_action")
    private String scheduledChangeAction;

    @Column(name = "scheduled_change_effective_at")
    private Instant scheduledChangeEffectiveAt;

    @Column(name = "canceled_at")
    private Instant canceledAt;

    @Column(name = "last_event_occurred_at", nullable = false)
    private Instant lastEventOccurredAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public BillingSubscription(
            String subscriptionId,
            UUID userId,
            String customerId,
            String status,
            String priceId,
            Instant currentPeriodEndsAt,
            String scheduledChangeAction,
            Instant scheduledChangeEffectiveAt,
            Instant canceledAt,
            Instant lastEventOccurredAt
    ) {
        this.subscriptionId = subscriptionId;
        this.userId = userId;
        this.customerId = customerId;
        this.status = status;
        this.priceId = priceId;
        this.currentPeriodEndsAt = currentPeriodEndsAt;
        this.scheduledChangeAction = scheduledChangeAction;
        this.scheduledChangeEffectiveAt = scheduledChangeEffectiveAt;
        this.canceledAt = canceledAt;
        this.lastEventOccurredAt = lastEventOccurredAt;
    }

    @PrePersist
    void prePersist() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void preUpdate() {
        this.updatedAt = Instant.now();
    }
}
