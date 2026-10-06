package com.history.backend.oauth;

import org.springframework.boot.context.properties.ConfigurationProperties;

// 인가 서버 주소들의 IP별 분당 호출 상한과, 요청자 IP를 읽을 헤더(비우면 연결 주소를 쓴다)
@ConfigurationProperties(prefix = "mcp.oauth.rate-limit")
public record OAuthRateLimitProperties(int perMinute, String clientIpHeader) {

    // 0 이하면 리미터가 빈 윈도우에서 예외를 내 모든 요청이 원인 모를 오류로 끝난다.
    // 조용한 전면 장애 대신 기동 시점의 설정 오류로 드러나게 한다.
    public OAuthRateLimitProperties {
        if (perMinute < 1) {
            throw new IllegalArgumentException("mcp.oauth.rate-limit.per-minute must be at least 1.");
        }
    }
}
