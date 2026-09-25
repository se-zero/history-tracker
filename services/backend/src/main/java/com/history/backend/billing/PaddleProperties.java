package com.history.backend.billing;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

// Paddle 결제 웹훅 설정. webhook-secret이 비면 PaddleSignatureVerifier가 모든 알림을 거부한다
// (fail-closed) — 서명 키 없이 통과시키는 것보다, 미설정 환경에서 알림이 전부 401로 재시도되는 쪽이 안전하다.
// pro-price-id가 비면 모든 가격의 구독을 허용한다(가격 검사를 생략).
@ConfigurationProperties(prefix = "paddle")
public record PaddleProperties(
        String webhookSecret,
        Duration signatureTolerance,
        String proPriceId
) {
}
