package com.history.backend.mcp;

import org.springframework.boot.context.properties.ConfigurationProperties;

// MCP 질의의 사용자별 분당 호출 상한
@ConfigurationProperties(prefix = "mcp.rate-limit")
public record McpRateLimitProperties(int perMinute) {

    // 0 이하면 McpRateLimiter가 빈 윈도우에서 예외를 내 모든 질의가 원인 모를 오류로 끝난다.
    // 조용한 전면 장애 대신 기동 시점의 설정 오류로 드러나게 한다.
    public McpRateLimitProperties {
        if (perMinute < 1) {
            throw new IllegalArgumentException("mcp.rate-limit.per-minute must be at least 1.");
        }
    }
}
