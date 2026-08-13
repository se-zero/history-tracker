package com.history.backend.discord;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "discord")
public record DiscordProperties(
        String clientId,
        String clientSecret,
        String redirectUri,
        // 앱 전체가 공유하는 봇 토큰 — 수집(pipeline-worker)과 연동 해제 시 길드 퇴장(backend) 양쪽에 쓰인다
        String botToken,
        String scopes,
        String permissions,
        String authorizeUrl,
        String tokenUrl,
        String revokeUrl,
        String apiBaseUrl
) {
}
