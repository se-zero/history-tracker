package com.history.backend.billing.service;

import java.time.Instant;
import java.util.List;

// Paddle 구독 엔티티에서 반영에 필요한 필드만 뽑은 값 — 웹훅 data와 API 응답 data가 같은 모양이라
// 둘 다 이 형태로 PaddleSubscriptionApplier에 들어간다. 누락·JSON null은 null이며, priceIds는
// items가 없거나 배열이 아닐 때 null(빈 배열은 빈 리스트)이다.
// customDataUserId는 UUID 검증 전 원문이다.
public record PaddleSubscriptionState(
        String subscriptionId,
        String status,
        String customerId,
        List<String> priceIds,
        String customDataUserId,
        String scheduledChangeAction,
        Instant scheduledChangeEffectiveAt,
        Instant canceledAt,
        Instant currentPeriodEndsAt,
        Instant nextBilledAt,
        Instant updatedAt
) {
}
