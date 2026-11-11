package com.history.backend.billing.service;

import java.util.List;

// Paddle 구독 상태 집합의 단일 출처. 반영(applier)·결제 차단(BillingAccountService)·해지 대상
// (SubscriptionCancellationService)·동기화가 각자 목록을 들고 있으면 한 곳만 고쳐 어긋난다.
public final class PaddleSubscriptionStatuses {

    // Pro를 주고 새 결제를 막는 상태. 연체(past_due) 동안 Pro 유지 — Paddle 권장이고,
    // 재시도가 끝내 실패하면 Paddle이 canceled를 보낸다. paused는 일시정지라 강등 대상이다.
    public static final List<String> ACTIVE_LIKE = List.of("active", "trialing", "past_due");

    // 강등 대상 상태
    public static final List<String> INACTIVE_LIKE = List.of("canceled", "paused");

    // 아직 끝나지 않은 상태. paused는 재개되면 다시 결제되므로 해지·동기화 대상에는 넣는다.
    public static final List<String> NON_TERMINAL = List.of("active", "trialing", "past_due", "paused");

    private PaddleSubscriptionStatuses() {
    }
}
