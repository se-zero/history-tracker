package com.history.backend.billing;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

// 결제 만료 유예 설정. expiry-grace만큼 plan_expires_at을 늘려 잡아, 갱신 알림이 늦거나 Paddle
// 재시도가 진행 중일 때 만료 스케줄러(PlanExpiryService)가 방금 결제한 계정을 먼저 강등하는
// 경합을 막는다. 기본값은 Paddle 라이브 재시도 창(~3일)과 같다.
@ConfigurationProperties(prefix = "app.billing")
public record BillingProperties(
        Duration expiryGrace
) {
}
