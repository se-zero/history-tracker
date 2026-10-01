package com.history.backend.billing.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.UuidGenerator;

// Paddle 결제 알림 수신 원장 — claim(tryClaim)과 처리 결과 기록(updateOutcome)은 전부 네이티브
// 쿼리로 쓰고, 이 엔티티는 ddl-auto: validate가 검증할 스키마 매핑 용도다.
// 원본 payload는 저장하지 않는다 — customer 이벤트에 이메일·이름 등 개인정보가 담겨 있다.
@Getter
@Entity
@Table(name = "billing_events")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class BillingEvent {

    @Id
    @GeneratedValue
    @UuidGenerator
    private UUID id;

    @Column(name = "event_id", nullable = false)
    private String eventId;

    @Column(name = "event_type", nullable = false)
    private String eventType;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(name = "notification_id")
    private String notificationId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private BillingEventOutcome outcome;

    @Column(name = "subscription_id")
    private String subscriptionId;

    // FK 아님 — 사용자가 파기된 뒤에도 결제 원장은 남겨야 한다. users FK로 걸면 CASCADE로
    // 원장까지 함께 사라져 감사 이력이 끊긴다.
    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;
}
