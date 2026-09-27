package com.history.backend.billing;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

// Paddle 결제 설정. webhook-secret이 비면 PaddleSignatureVerifier가 모든 알림을 거부한다
// (fail-closed) — 서명 키 없이 통과시키는 것보다, 미설정 환경에서 알림이 전부 401로 재시도되는 쪽이 안전하다.
// pro-price-id가 비면 웹훅의 가격 검사를 생략한다(모든 가격의 구독을 허용).
// 결제 버튼은 api-key·client-token·pro-price-id가 모두 있을 때만 연다(isCheckoutConfigured).
// 하나라도 비면 화면은 "준비 중"이라, 라이브 심사 전에 코드를 배포해도 결제가 열리지 않는다.
@ConfigurationProperties(prefix = "paddle")
public record PaddleProperties(
        String webhookSecret,
        Duration signatureTolerance,
        String proPriceId,
        String apiKey,
        String environment,
        String clientToken
) {
    public boolean isCheckoutConfigured() {
        return isNotBlank(apiKey) && isNotBlank(clientToken) && isNotBlank(proPriceId);
    }

    // environment가 production이 아니면 샌드박스. 기본값을 라이브로 두면 설정 누락이 실결제가 된다.
    public String apiBaseUrl() {
        return "production".equals(environment)
                ? "https://api.paddle.com"
                : "https://sandbox-api.paddle.com";
    }

    private static boolean isNotBlank(String value) {
        return value != null && !value.isBlank();
    }
}
