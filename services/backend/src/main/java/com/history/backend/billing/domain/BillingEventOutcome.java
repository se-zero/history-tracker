package com.history.backend.billing.domain;

// Paddle 웹훅 알림 처리 결과. tryClaim 직후 RECEIVED로 시작하고, 처리가 끝나면
// APPLIED·STALE·UNMATCHED·IGNORED 중 하나로 갱신된다(중복 알림은 행 자체가 없어 별도 값이 없다).
public enum BillingEventOutcome {
    RECEIVED,
    APPLIED,
    STALE,
    UNMATCHED,
    IGNORED
}
