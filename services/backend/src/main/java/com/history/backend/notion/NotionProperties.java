package com.history.backend.notion;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "notion")
public record NotionProperties(
        String clientId,
        String clientSecret,
        String redirectUri,
        String authorizeUrl,
        String tokenUrl,
        String revokeUrl,
        String apiBaseUrl,
        // Notion-Version 헤더 고정값 — Notion은 URL이 아니라 헤더로 API 버전을 가른다. 배포
        // 시점마다 계정 기본 버전이 달라져 응답 형태가 바뀌는 것을 막기 위해 상수로 고정하고
        // 모든 요청에 싣는다.
        String version
) {
}
