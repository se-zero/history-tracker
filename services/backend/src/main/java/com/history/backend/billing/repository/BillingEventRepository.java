package com.history.backend.billing.repository;

import java.time.Instant;
import java.util.UUID;

import com.history.backend.billing.domain.BillingEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BillingEventRepository extends JpaRepository<BillingEvent, UUID> {

    // (event_id) 선점 insert — 이미 수신한 알림(재시도 포함)이면 0 반환 (멱등 처리)
    @Modifying
    @Query(
            value = """
                    INSERT INTO billing_events (
                        event_id, event_type, occurred_at, notification_id, outcome, received_at
                    )
                    VALUES (:eventId, :eventType, :occurredAt, :notificationId, 'RECEIVED', :receivedAt)
                    ON CONFLICT (event_id) DO NOTHING
                    """,
            nativeQuery = true
    )
    int tryClaim(
            @Param("eventId") String eventId,
            @Param("eventType") String eventType,
            @Param("occurredAt") Instant occurredAt,
            @Param("notificationId") String notificationId,
            @Param("receivedAt") Instant receivedAt
    );

    @Modifying
    @Query(
            value = """
                    UPDATE billing_events
                    SET outcome = :outcome, subscription_id = :subscriptionId, user_id = :userId
                    WHERE event_id = :eventId
                    """,
            nativeQuery = true
    )
    int updateOutcome(
            @Param("eventId") String eventId,
            @Param("outcome") String outcome,
            @Param("subscriptionId") String subscriptionId,
            @Param("userId") UUID userId
    );
}
